"""On-demand HLS: a segment is generated only when a player requests it."""

from __future__ import annotations

import asyncio
import contextlib
import shutil
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path

from loguru import logger

from src.core.base import BaseService
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.download.interface import TaskRepo
from src.lib.media.ffmpeg import run as ffmpeg_run
from src.lib.media.ffmpeg import segment_args
from src.lib.media.ffprobe import ProbeResult, probe
from src.lib.torrent.protocol import TorrentClient
from src.lib.torrent.source import parse_source
from src.service.stream.session import SegmentState, StreamSession, StreamSessionStore
from src.service.stream.torrent_source import pick_media_file

Prober = Callable[[str, str], Awaitable[ProbeResult]]
Encoder = Callable[[list[str]], Awaitable[None]]


class StreamService(BaseService):
    def __init__(
        self,
        sessions: StreamSessionStore,
        stream_dir: Path,
        ffmpeg_path: str,
        ffprobe_path: str,
        segment_seconds: int,
        readahead_segments: int,
        max_concurrent_encodes: int,
        prober: Prober = probe,
        encoder: Encoder = ffmpeg_run,
        torrent_client: TorrentClient | None = None,
        task_repo: TaskRepo | None = None,
        torrent_dir: Path | None = None,
        torrent_api_url: str = "",
        torrent_enabled: bool = True,
    ) -> None:
        super().__init__()
        self._sessions = sessions
        self._stream_dir = stream_dir
        self._ffmpeg_path = ffmpeg_path
        self._ffprobe_path = ffprobe_path
        self._segment_seconds = segment_seconds
        self._readahead = readahead_segments
        self._max_concurrent_encodes = max_concurrent_encodes
        self._prober = prober
        self._encoder = encoder
        self._torrent_client = torrent_client
        self._task_repo = task_repo
        self._torrent_dir = torrent_dir
        self._torrent_api_url = torrent_api_url
        self._torrent_enabled = torrent_enabled

    async def start_session(self, source_url: str) -> StreamSession:
        result = await self._prober(self._ffprobe_path, source_url)
        session_id = uuid.uuid4().hex
        session_dir = self._stream_dir / session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        session = StreamSession(
            id=session_id,
            source_url=source_url,
            duration_seconds=result.duration_seconds,
            has_video=result.has_video,
            segment_seconds=self._segment_seconds,
            session_dir=session_dir,
            encode_semaphore=asyncio.Semaphore(self._max_concurrent_encodes),
        )
        self._sessions.add(session)
        return session

    async def start_torrent_session(self, torrent_raw: str) -> StreamSession:
        if not self._torrent_enabled:
            raise Error.service_unavailable("Torrent support is disabled")
        if self._torrent_client is None or self._task_repo is None or self._torrent_dir is None:
            raise Error.create(
                code=Code.INTERNAL_SERVER_ERROR,
                message="Torrent streaming is not configured",
                error_type=ErrorType.SERVER_ERROR,
            )

        source = parse_source(torrent_raw)
        details = await self._torrent_client.resolve(source)
        target = pick_media_file(details.files)
        if target is None:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message="This torrent has no playable media file",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )

        await self._torrent_client.add(
            source,
            only_files=[target.index],
            output_folder=str(self._torrent_dir),
        )

        stream_url = f"{self._torrent_api_url}/torrents/{details.info_hash}/stream/{target.index}"
        session = await self.start_session(stream_url)
        session.info_hash = details.info_hash
        return session

    def get_session(self, session_id: str) -> StreamSession:
        session = self._sessions.get(session_id)
        if session is None:
            raise Error.not_found("Stream session not found")
        return session

    def playlist_text(self, session: StreamSession) -> str:
        lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            f"#EXT-X-TARGETDURATION:{session.segment_seconds}",
            "#EXT-X-PLAYLIST-TYPE:VOD",
        ]
        for index in range(session.segment_count):
            # Every segment past the first is its own independent ffmpeg
            # encode with its own near-zero-restarting timestamps — see
            # segment_args()'s docstring. This tells the player to remap each
            # one to its playlist position instead of expecting the raw
            # timestamps to already be continuous across the boundary.
            if index > 0:
                lines.append("#EXT-X-DISCONTINUITY")
            lines.append(f"#EXTINF:{session.segment_duration(index):.3f},")
            lines.append(f"segment_{index}.ts")
        lines.append("#EXT-X-ENDLIST")
        return "\n".join(lines) + "\n"

    async def get_segment(self, session: StreamSession, index: int) -> Path:
        if index < 0 or index >= session.segment_count:
            raise Error.not_found(f"Segment {index} does not exist")
        session.touch()
        await self._ensure_segment(session, index)
        for ahead in range(1, self._readahead + 1):
            next_index = index + ahead
            if (
                next_index < session.segment_count
                and session.state_of(next_index) == SegmentState.NOT_STARTED
            ):
                task = asyncio.create_task(self._ensure_segment_quietly(session, next_index))
                session.background_tasks.append(task)
        return session.segment_path(index)

    async def _ensure_segment_quietly(self, session: StreamSession, index: int) -> None:
        try:
            await self._ensure_segment(session, index)
        except Exception:
            # Read-ahead is best-effort. A failure here just leaves the
            # segment NOT_STARTED, and it gets a real attempt (with its
            # error propagated) the moment a player actually requests it via
            # the synchronous path in get_segment.
            logger.warning("StreamService|read-ahead failed for segment {}", index)

    async def _ensure_segment(self, session: StreamSession, index: int) -> None:
        state = session.state_of(index)
        if state == SegmentState.READY:
            return
        event = session.event_for(index)
        if state == SegmentState.GENERATING:
            await event.wait()
            return

        # No ``await`` between the check above and this assignment — see
        # StreamSession's docstring for why that makes this race-free.
        session.states[index] = SegmentState.GENERATING
        async with session.encode_semaphore:
            args = segment_args(
                self._ffmpeg_path,
                session.source_url,
                start_seconds=index * session.segment_seconds,
                duration_seconds=session.segment_duration(index),
                destination=session.segment_path(index),
                has_video=session.has_video,
            )
            await self._encoder(args)
        session.states[index] = SegmentState.READY
        event.set()

    async def stop_session(self, session_id: str) -> None:
        session = self._sessions.remove(session_id)
        if session is None:
            return

        # Cancelling a task doesn't cancel it *now* — it schedules
        # CancelledError for the next time that task runs, and does nothing
        # to any subprocess it's awaiting. Without waiting for the tasks
        # here, rmtree below can race an ffmpeg process that's still writing
        # into the very directory being deleted. (`run()` in ffmpeg.py is
        # what actually kills that process, once its task is cancelled.)
        for task in session.background_tasks:
            task.cancel()
        for task in session.background_tasks:
            with contextlib.suppress(Exception, asyncio.CancelledError):
                await task

        if session.info_hash and self._torrent_client is not None and self._task_repo is not None:
            existing = await self._task_repo.get_one(
                info_hash=session.info_hash, deleted_at__isnull=True
            )
            if existing is None:
                try:
                    await self._torrent_client.delete(session.info_hash)
                except Error as error:
                    logger.warning(
                        "StreamService|torrent delete failed for {}: {}",
                        session.info_hash,
                        error.message,
                    )

        await asyncio.to_thread(shutil.rmtree, session.session_dir, ignore_errors=True)
