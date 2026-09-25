"""On-demand HLS: a segment is generated only when a player requests it."""

from __future__ import annotations

import asyncio
import contextlib
import shutil
import uuid
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from urllib.parse import urlparse

import httpx
from loguru import logger

from src.core.base import BaseService
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.download.interface import TaskRepo
from src.lib.event import EventHub
from src.lib.media.ffmpeg import run as ffmpeg_run
from src.lib.media.ffmpeg import segment_args
from src.lib.media.ffprobe import ProbeResult, probe
from src.lib.media.hls import MediaPlaylist, PlaylistRefused, parse_media_playlist, sub_playlist
from src.lib.media.media_type import media_type
from src.lib.media.source import MediaInput, PlaylistCut
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient
from src.lib.site.format import playback_plan
from src.lib.torrent.folder import torrent_folder
from src.lib.torrent.protocol import TorrentClient, TorrentProgress
from src.lib.torrent.source import parse_source
from src.service.stream.session import SegmentState, SiteOrigin, StreamSession, StreamSessionStore
from src.service.stream.torrent_source import MEDIA_EXTENSIONS, pick_media_file


class Prober(Protocol):
    def __call__(
        self, ffprobe: str, source: str, /, *, headers: Mapping[str, str] | None = None
    ) -> Awaitable[ProbeResult]: ...


Encoder = Callable[[list[str]], Awaitable[None]]

#: Fetches a media playlist with its format's headers. Returns the URL it was
#: read from in the end, which its relative URIs resolve against, and its text.
PlaylistFetcher = Callable[[str, Mapping[str, str]], Awaitable[tuple[str, str]]]

#: A finished task's file on disk, by task and torrent file index: its path,
#: its name, and the index it turned out to be. ``DownloadService`` owns the
#: rules (finished, present, inside its folder), so a path never comes from a
#: client.
TaskFiles = Callable[[uuid.UUID, int | None], Awaitable[tuple[Path, str, int | None]]]

_PLAYLIST_TIMEOUT_S = 20.0


@dataclass(frozen=True, slots=True)
class MediaInfo:
    """What the player needs to choose between the file itself and a session (#94)."""

    file_index: int | None
    filename: str
    duration_seconds: float
    has_video: bool
    #: For ``canPlayType``. ``None`` when no browser plays it from a file.
    media_type: str | None


async def fetch_playlist(
    url: str, headers: Mapping[str, str], *, transport: httpx.AsyncBaseTransport | None = None
) -> tuple[str, str]:
    """The default ``PlaylistFetcher``. A playlist that won't load is a retryable 502."""
    try:
        async with httpx.AsyncClient(
            timeout=_PLAYLIST_TIMEOUT_S, follow_redirects=True, transport=transport
        ) as client:
            response = await client.get(url, headers=dict(headers))
            response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        raise site_error.playlist_failed(f"status {exc.response.status_code}") from exc
    except httpx.HTTPError as exc:
        raise site_error.playlist_failed(str(exc) or type(exc).__name__) from exc
    return str(response.url), response.text


@dataclass(frozen=True, slots=True)
class _Page:
    """What a page's site offers the player."""

    inputs: list[MediaInput]
    origin: SiteOrigin
    #: Its inputs are HLS media playlists: read rather than probed, and cut from.
    hls: bool
    has_video: bool


def _is_media_file(url: str) -> bool:
    """A link to a file the player can read as it is, with nothing to extract."""
    return urlparse(url).path.lower().endswith(MEDIA_EXTENSIONS)


def _refused(error: Error) -> bool:
    """Whether ffmpeg's server said 403: how a site's expired media URL fails."""
    return "403 forbidden" in (error.message or "").lower()


def _session_not_found() -> Error:
    return Error.not_found("Stream session not found")


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
        event_hub: EventHub | None = None,
        progress_poll_s: float = 1.0,
        site_client: SiteClient | None = None,
        playlist_fetcher: PlaylistFetcher = fetch_playlist,
        task_files: TaskFiles | None = None,
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
        self._event_hub = event_hub
        self._progress_poll_s = progress_poll_s
        self._site_client = site_client
        self._playlist_fetcher = playlist_fetcher
        self._task_files = task_files

    async def media_info(self, task_id: uuid.UUID, file_index: int | None) -> MediaInfo:
        """Probe a finished task's file for what the player needs to pick how to play it."""
        path, filename, index = await self._task_file(task_id, file_index)
        result = await self._prober(self._ffprobe_path, str(path))
        return MediaInfo(
            file_index=index,
            filename=filename,
            duration_seconds=result.duration_seconds,
            has_video=result.has_video,
            media_type=media_type(result.container, result.video_codec, result.audio_codec),
        )

    async def start_task_session(self, task_id: uuid.UUID, file_index: int | None) -> StreamSession:
        """Play a finished task's file through a session, read from disk (#94).

        For what the browser can't play itself: the file is probed and cut
        like any other source, but it is never fetched again.
        """
        path, _filename, _index = await self._task_file(task_id, file_index)
        source = MediaInput(str(path))
        result = await self._prober(self._ffprobe_path, source.url)
        return self._new_session([source], result.duration_seconds, result.has_video)

    async def _task_file(self, task_id: uuid.UUID, file_index: int | None) -> tuple[Path, str, int | None]:
        if self._task_files is None:
            raise Error.service_unavailable("Playing downloads is not configured")
        return await self._task_files(task_id, file_index)

    def _new_session(
        self,
        inputs: list[MediaInput],
        duration: float,
        has_video: bool,
        *,
        origin: SiteOrigin | None = None,
        playlists: list[MediaPlaylist] | None = None,
    ) -> StreamSession:
        session_id = uuid.uuid4().hex
        session_dir = self._stream_dir / session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        session = StreamSession(
            id=session_id,
            inputs=inputs,
            duration_seconds=duration,
            has_video=has_video,
            segment_seconds=self._segment_seconds,
            session_dir=session_dir,
            encode_semaphore=asyncio.Semaphore(self._max_concurrent_encodes),
            origin=origin,
            playlists=playlists or [],
        )
        self._sessions.add(session)
        return session

    async def start_session(self, url: str) -> StreamSession:
        """Play ``url``: a page on a site, or a media file as it is.

        A page is read from the formats its site offers; anything no site
        claims is played directly, the same fallback the add box makes. An HLS
        page isn't probed: its playlists say how long it is, and its segments
        are cut from them.
        """
        page = await self._open_page(url)
        inputs = page.inputs if page is not None else [MediaInput(url)]
        playlists: list[MediaPlaylist] = []
        if page is not None and page.hls:
            playlists = await self._fetch_playlists(inputs)
            duration, has_video = playlists[0].duration, page.has_video
        else:
            first = inputs[0]
            result = await self._prober(self._ffprobe_path, first.url, headers=first.headers)
            duration, has_video = result.duration_seconds, result.has_video
        return self._new_session(
            inputs,
            duration,
            has_video,
            origin=page.origin if page is not None else None,
            playlists=playlists,
        )

    async def _open_page(self, url: str) -> _Page | None:
        """What a page's site offers for playback, or ``None`` for a plain file."""
        if self._site_client is None or _is_media_file(url):
            return None
        try:
            info, resolved = await self._site_client.open(url)
        except Error as error:
            if error.type == ErrorType.UNSUPPORTED_URL:
                return None
            raise
        if info.is_live:
            raise site_error.live_not_supported()
        plan = playback_plan(info.formats)
        parts = [part for part in (plan.video, plan.audio) if part is not None]
        return _Page(
            inputs=[MediaInput(resolved[part.id].url, resolved[part.id].headers) for part in parts],
            origin=SiteOrigin(info.webpage_url or url, tuple(part.id for part in parts)),
            hls=all(part.hls for part in parts),
            has_video=plan.video is not None,
        )

    async def _fetch_playlists(self, inputs: list[MediaInput]) -> list[MediaPlaylist]:
        """Each input's media playlist, fetched together."""
        return list(await asyncio.gather(*(self._fetch_playlist(source) for source in inputs)))

    async def _fetch_playlist(self, source: MediaInput) -> MediaPlaylist:
        final_url, text = await self._playlist_fetcher(source.url, source.headers)
        try:
            return parse_media_playlist(final_url, text)
        except PlaylistRefused as refused:
            raise (site_error.live_not_supported() if refused.live else site_error.stream_not_playable()) from refused

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

        # A folder of its own, so a streamed file can't overwrite a download's
        # (#107). A torrent a task already has keeps its own: rqbit ignores a
        # re-add's options (#93).
        await self._torrent_client.add(
            source,
            only_files=[target.index],
            output_folder=str(torrent_folder(self._torrent_dir, details.name, details.info_hash)),
        )

        stream_url = f"{self._torrent_api_url}/torrents/{details.info_hash}/stream/{target.index}"
        session_id = uuid.uuid4().hex
        session_dir = self._stream_dir / session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        session = StreamSession(
            id=session_id,
            inputs=[MediaInput(stream_url)],
            duration_seconds=0.0,
            has_video=True,
            segment_seconds=self._segment_seconds,
            session_dir=session_dir,
            encode_semaphore=asyncio.Semaphore(self._max_concurrent_encodes),
            info_hash=details.info_hash,
            status="connecting",
        )
        self._sessions.add(session)
        task = asyncio.create_task(self._probe_torrent_session(session))
        session.background_tasks.append(task)
        return session

    async def _probe_torrent_session(self, session: StreamSession) -> None:
        poll_task = asyncio.create_task(self._publish_progress_until_cancelled(session))
        session.progress_task = poll_task
        try:
            result = await self._prober(self._ffprobe_path, session.inputs[0].url)
        except Error as error:
            session.status = "error"
            session.error = error.message
            self._publish_status(session, status="error", message=error.message)
            poll_task.cancel()
            with contextlib.suppress(Exception, asyncio.CancelledError):
                await poll_task
            return
        session.duration_seconds = result.duration_seconds
        session.has_video = result.has_video
        session.status = "ready"
        self._publish_status(session, status="ready")
        # poll_task keeps running after this — it's what keeps the player's
        # swarm HUD live during playback, not just while connecting. It
        # stops only when stop_session() cancels it.

    async def _publish_progress_until_cancelled(self, session: StreamSession) -> None:
        assert session.info_hash is not None
        while True:
            progress = await self._progress_for(session.info_hash)
            if progress is not None:
                self._publish_status(
                    session,
                    status=session.status,
                    peers_connected=progress.peers_connected,
                    download_bps=progress.download_bps,
                    progress_bytes=progress.progress_bytes,
                    total_bytes=progress.total_bytes,
                )
            await asyncio.sleep(self._progress_poll_s)

    async def _progress_for(self, info_hash: str) -> TorrentProgress | None:
        assert self._torrent_client is not None
        for row in await self._torrent_client.list_progress():
            if row.info_hash == info_hash:
                return row
        return None

    def _publish_status(self, session: StreamSession, *, status: str, **fields: object) -> None:
        if self._event_hub is None:
            return
        self._event_hub.publish("stream_status", {"id": session.id, "status": status, **fields})

    def get_session(self, session_id: str) -> StreamSession:
        session = self._sessions.get(session_id)
        if session is None:
            raise _session_not_found()
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
        # The player may close while this waits. stop_session takes the
        # session out of the store, then deletes its folder, so whatever the
        # encode made of that is answered as the session having gone.
        try:
            await self._ensure_segment(session, index)
        except (Error, OSError) as error:
            if self._is_open(session):
                raise
            raise _session_not_found() from error
        if not self._is_open(session):
            raise _session_not_found()
        for ahead in range(1, self._readahead + 1):
            next_index = index + ahead
            if (
                next_index < session.segment_count
                and session.state_of(next_index) == SegmentState.NOT_STARTED
            ):
                task = asyncio.create_task(self._ensure_segment_quietly(session, next_index))
                session.background_tasks.append(task)
        return session.segment_path(index)

    def _is_open(self, session: StreamSession) -> bool:
        return self._sessions.get(session.id) is session

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
        # A loop, because waking up is not the same as the segment being
        # ready: an encode that fails wakes its waiters too, and each then
        # makes an attempt of its own.
        while True:
            state = session.state_of(index)
            if state == SegmentState.READY:
                return
            event = session.event_for(index)
            if state != SegmentState.GENERATING:
                break
            await event.wait()

        # No ``await`` between the check above and this assignment — see
        # StreamSession's docstring for why that makes this race-free.
        session.states[index] = SegmentState.GENERATING
        try:
            async with session.encode_semaphore:
                await self._encode(session, index)
        except BaseException:
            # Cancellation included. Left GENERATING, the segment would make
            # every later request wait on an event nothing would ever set. The
            # next attempt gets a fresh event; this one wakes the waiters.
            session.states[index] = SegmentState.NOT_STARTED
            session.events.pop(index, None)
            event.set()
            raise
        session.states[index] = SegmentState.READY
        event.set()

    async def _encode(self, session: StreamSession, index: int) -> None:
        """Encode one segment; a site's expired URLs are resolved again, once."""
        if not self._is_open(session):
            # Stopped while this waited its turn: nothing to encode into.
            raise _session_not_found()
        version = session.inputs_version
        try:
            await self._encoder(await self._segment_args(session, index))
        except Error as error:
            if session.origin is None or not _refused(error):
                raise
            logger.info("StreamService|{} refused segment {}; resolving its URLs again", session.id, index)
            await self._refresh_inputs(session, version)
            await self._encoder(await self._segment_args(session, index))

    async def _segment_args(self, session: StreamSession, index: int) -> list[str]:
        start = index * session.segment_seconds
        duration = session.segment_duration(index)
        inputs: list[MediaInput] | list[PlaylistCut] = session.inputs
        if session.playlists:
            inputs = await self._cut(session, index, start, start + duration)
        return segment_args(
            self._ffmpeg_path,
            inputs,
            start_seconds=start,
            duration_seconds=duration,
            destination=session.segment_path(index),
            has_video=session.has_video,
        )

    async def _cut(self, session: StreamSession, index: int, start: float, end: float) -> list[PlaylistCut]:
        """Each input's playlist of the fragments segment ``index`` overlaps, written beside the segment."""
        cuts: list[PlaylistCut] = []
        for n, playlist in enumerate(session.playlists):
            text, first = sub_playlist(playlist, start, end)
            path = session.session_dir / f"segment_{index}.{n}.m3u8"
            await asyncio.to_thread(path.write_text, text, encoding="utf-8")
            cuts.append(PlaylistCut(path, first))
        return cuts

    async def _refresh_inputs(self, session: StreamSession, seen_version: int) -> None:
        """Ask the site for fresh URLs, unless a segment refused alongside already has.

        An HLS session reads its playlists again too, since its fragments'
        URLs are in them. Both are replaced only once everything has loaded.
        """
        assert session.origin is not None and self._site_client is not None
        async with session.refresh_lock:
            if session.inputs_version != seen_version:
                return
            origin = session.origin
            resolved = await self._site_client.resolve(origin.page_url, origin.format_ids)
            inputs = [MediaInput(resolved[i].url, resolved[i].headers) for i in origin.format_ids]
            if session.playlists:
                session.playlists = await self._fetch_playlists(inputs)
            session.inputs = inputs
            session.inputs_version += 1

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
        tasks = list(session.background_tasks)
        if session.progress_task is not None:
            tasks.append(session.progress_task)
        for task in tasks:
            task.cancel()
        for task in tasks:
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
