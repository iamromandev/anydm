"""On-demand HLS: a segment is generated only when a player requests it."""

from __future__ import annotations

import asyncio
import contextlib
import os
import shutil
import uuid
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import TYPE_CHECKING, Protocol
from urllib.parse import urlparse

import httpx
from loguru import logger

from src.core.base import BaseService
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.download.interface import TaskRepo
from src.lib.event import EventHub
from src.lib.media.audio import AudioTrack, pick_audio_track
from src.lib.media.ffmpeg import run as ffmpeg_run
from src.lib.media.ffmpeg import segment_args, subtitle_args, subtitle_file_args
from src.lib.media.ffprobe import ProbeResult, probe
from src.lib.media.hls import MediaPlaylist, PlaylistRefused, parse_media_playlist, sub_playlist
from src.lib.media.media_type import media_type
from src.lib.media.sidecar import (
    Sidecar,
    SidecarSource,
    SiteSubtitleFile,
    TorrentFile,
    decode_subtitles,
    match_sidecars,
    sidecar_codec,
)
from src.lib.media.source import MediaInput, PlaylistCut
from src.lib.media.subtitle import SubtitleTrack
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient
from src.lib.site.format import audio_choices, playback_plan
from src.lib.site.subtitles import SiteSubtitle, fetch_subtitle, same_subtitle
from src.lib.torrent.folder import torrent_folder
from src.lib.torrent.protocol import TorrentClient, TorrentProgress
from src.lib.torrent.source import parse_source
from src.service.stream.session import SegmentState, SiteOrigin, StreamSession, StreamSessionStore
from src.service.stream.torrent_source import MEDIA_EXTENSIONS, pick_media_file

if TYPE_CHECKING:
    from src.service.download.download_service import TorrentPlay


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

#: The torrent to stream for a task still downloading, or ``None`` when it
#: plays from disk (``DownloadService.torrent_play``, #95).
TorrentPlays = Callable[[uuid.UUID, int | None], Awaitable["TorrentPlay | None"]]

#: The subtitle files beside a task's file, and where to read each
#: (``DownloadService.subtitle_files``, #101).
TaskSidecars = Callable[[uuid.UUID, int | None], Awaitable[list[tuple[Sidecar, SidecarSource]]]]

#: Reads a URL whole: a subtitle file out of rqbit.
BytesFetcher = Callable[[str], Awaitable[bytes]]

#: Reads a site's subtitle file with the headers its server expects (#102).
SubtitleFetcher = Callable[[str, Mapping[str, str]], Awaitable[bytes]]

#: Far more than any subtitle file; a guard against being handed a film.
_SIDECAR_MAX_BYTES = 10 * 1024 * 1024
_SIDECAR_TIMEOUT_S = 60.0

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
    #: A browser playing the file itself opens with the one marked default;
    #: any other takes a session (#99).
    audio_tracks: tuple[AudioTrack, ...] = ()
    #: A browser playing the file itself shows them from whole-track WebVTT (#100).
    subtitle_tracks: tuple[SubtitleTrack, ...] = ()


async def fetch_bytes(url: str, *, transport: httpx.AsyncBaseTransport | None = None) -> bytes:
    """The default ``BytesFetcher``. A file that won't load is a retryable 502."""
    try:
        async with httpx.AsyncClient(timeout=_SIDECAR_TIMEOUT_S, transport=transport) as client:
            response = await client.get(url)
            response.raise_for_status()
    except httpx.HTTPError as exc:
        raise Error.create(
            code=Code.BAD_GATEWAY,
            message=f"The subtitle file could not be read: {exc}",
            error_type=ErrorType.EXTERNAL_API_ERROR,
            retry_able=True,
        ) from exc
    if len(response.content) > _SIDECAR_MAX_BYTES:
        raise Error.create(
            code=Code.UNPROCESSABLE_ENTITY,
            message="That's too big to be a subtitle file",
            error_type=ErrorType.UNPROCESSABLE_ENTITY,
        )
    return response.content


def with_sidecars(
    embedded: Sequence[SubtitleTrack], sidecars: Sequence[tuple[Sidecar, SidecarSource]]
) -> tuple[list[SubtitleTrack], dict[int, tuple[Sidecar, SidecarSource]]]:
    """A source's subtitle tracks with its subtitle files after them, and each file by its index (#101)."""
    tracks = list(embedded)
    files: dict[int, tuple[Sidecar, SidecarSource]] = {}
    for offset, (sidecar, source) in enumerate(sidecars):
        index = len(embedded) + offset
        label = sidecar.title + (" · SDH" if sidecar.hearing_impaired else "")
        tracks.append(
            SubtitleTrack(
                index=index,
                language=sidecar.language,
                title=label,
                codec=sidecar_codec(sidecar.path),
                forced=sidecar.forced,
                external=True,
            )
        )
        files[index] = (sidecar, source)
    return tracks, files


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
    #: Its audio formats as tracks, and each one's id and input (#99).
    audio_tracks: list[AudioTrack]
    site_audio: list[tuple[str, MediaInput]]
    audio_track: int | None
    #: Its own subtitles and captions (#102), as subtitle files to fetch.
    subtitles: list[tuple[Sidecar, SidecarSource]]


def _site_subtitle(subtitle: SiteSubtitle) -> tuple[Sidecar, SidecarSource]:
    """A site's subtitles as one of the player's subtitle files (#102)."""
    return (
        Sidecar(path=f"{subtitle.language}.{subtitle.ext}", language=subtitle.language, title=subtitle.name),
        SiteSubtitleFile(
            subtitle.url, tuple(subtitle.headers.items()), language=subtitle.language, automatic=subtitle.automatic
        ),
    )


def _is_media_file(url: str) -> bool:
    """A link to a file the player can read as it is, with nothing to extract."""
    return urlparse(url).path.lower().endswith(MEDIA_EXTENSIONS)


def _refused(error: Error) -> bool:
    """Whether ffmpeg's server said 403: how a site's expired media URL fails."""
    return "403 forbidden" in (error.message or "").lower()


def _session_not_found() -> Error:
    return Error.not_found("Stream session not found")


async def _produce_once(
    states: dict[int, SegmentState],
    events: dict[int, asyncio.Event],
    index: int,
    produce: Callable[[], Awaitable[None]],
) -> None:
    """Run ``produce`` for ``index`` unless it's done or under way, in which case wait for it.

    A loop, because waking up is not the same as it being ready: an attempt
    that fails wakes its waiters too, and each then makes an attempt of its
    own. No ``await`` comes between the check and the flip to ``GENERATING``,
    which is what makes it race-free; see ``StreamSession``'s docstring.
    """
    while True:
        state = states.get(index, SegmentState.NOT_STARTED)
        if state == SegmentState.READY:
            return
        event = events.setdefault(index, asyncio.Event())
        if state != SegmentState.GENERATING:
            break
        await event.wait()

    states[index] = SegmentState.GENERATING
    try:
        await produce()
    except BaseException:
        # Cancellation included. Left GENERATING, it would make every later
        # request wait on an event nothing would ever set. The next attempt
        # gets a fresh event; this one wakes the waiters.
        states[index] = SegmentState.NOT_STARTED
        events.pop(index, None)
        event.set()
        raise
    states[index] = SegmentState.READY
    event.set()


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
        task_sidecars: TaskSidecars | None = None,
        bytes_fetcher: BytesFetcher = fetch_bytes,
        subtitle_fetcher: SubtitleFetcher = fetch_subtitle,
        torrent_play: TorrentPlays | None = None,
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
        self._task_sidecars = task_sidecars
        self._fetch_bytes = bytes_fetcher
        self._fetch_subtitle = subtitle_fetcher
        self._torrent_play = torrent_play

    async def media_info(self, task_id: uuid.UUID, file_index: int | None) -> MediaInfo:
        """Probe a finished task's file for what the player needs to pick how to play it."""
        path, filename, index = await self._task_file(task_id, file_index)
        result = await self._prober(self._ffprobe_path, str(path))
        subtitles, _ = with_sidecars(result.subtitle_tracks, await self._sidecars_of(task_id, index))
        return MediaInfo(
            file_index=index,
            filename=filename,
            duration_seconds=result.duration_seconds,
            has_video=result.has_video,
            media_type=media_type(result.container, result.video_codec, result.audio_codec),
            audio_tracks=result.audio_tracks,
            subtitle_tracks=tuple(subtitles),
        )

    async def start_task_session(
        self,
        task_id: uuid.UUID,
        file_index: int | None,
        *,
        audio_language: str | None = None,
        audio_track: int | None = None,
    ) -> StreamSession:
        """Play a finished task's file through a session, read from disk (#94).

        For what the browser can't play itself: the file is probed and cut
        like any other source, but it is never fetched again.

        A torrent still downloading plays from rqbit instead, through its own
        torrent: nothing is added, so the download's selection can't change (#95).

        ``audio_track`` is the track to play, else the one ``audio_language``
        picks (#99).
        """
        if self._torrent_play is not None:
            target = await self._torrent_play(task_id, file_index)
            if target is not None:
                return self._torrent_stream_session(
                    target.info_hash,
                    target.file_index,
                    audio_language=audio_language,
                    audio_track=audio_track,
                    sidecars=await self._sidecars_of(task_id, target.file_index),
                )
        path, _filename, index = await self._task_file(task_id, file_index)
        source = MediaInput(str(path))
        result = await self._prober(self._ffprobe_path, source.url)
        subtitles, files = with_sidecars(result.subtitle_tracks, await self._sidecars_of(task_id, index))
        session = self._new_session(
            [source],
            result.duration_seconds,
            result.has_video,
            audio_tracks=list(result.audio_tracks),
            audio_track=pick_audio_track(result.audio_tracks, audio_language, audio_track),
            subtitle_tracks=subtitles,
        )
        session.subtitle_files = files
        return session

    async def _sidecars_of(self, task_id: uuid.UUID, file_index: int | None) -> list[tuple[Sidecar, SidecarSource]]:
        """A task's subtitle files, or none when nothing can say (#101)."""
        if self._task_sidecars is None:
            return []
        return await self._task_sidecars(task_id, file_index)

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
        audio_tracks: list[AudioTrack] | None = None,
        audio_track: int | None = None,
        site_audio: list[tuple[str, MediaInput]] | None = None,
        info_hash: str | None = None,
        subtitle_tracks: list[SubtitleTrack] | None = None,
        subtitle_files: dict[int, tuple[Sidecar, SidecarSource]] | None = None,
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
            audio_tracks=audio_tracks or [],
            audio_track=audio_track,
            site_audio=site_audio or [],
            info_hash=info_hash,
            subtitle_tracks=subtitle_tracks or [],
            subtitle_files=subtitle_files or {},
        )
        self._sessions.add(session)
        return session

    async def start_session(
        self, url: str, *, audio_language: str | None = None, audio_track: int | None = None
    ) -> StreamSession:
        """Play ``url``: a page on a site, or a media file as it is.

        A page is read from the formats its site offers; anything no site
        claims is played directly, the same fallback the add box makes. An HLS
        page isn't probed: its playlists say how long it is, and its segments
        are cut from them.

        A page's audio tracks are its audio formats; a file's are its own (#99).
        """
        page = await self._open_page(url, audio_language, audio_track)
        inputs = page.inputs if page is not None else [MediaInput(url)]
        playlists: list[MediaPlaylist] = []
        if page is not None and page.hls:
            playlists = await self._fetch_playlists(inputs)
            duration, has_video = playlists[0].duration, page.has_video
        else:
            first = inputs[0]
            result = await self._prober(self._ffprobe_path, first.url, headers=first.headers)
            duration, has_video = result.duration_seconds, result.has_video
        # A page's own subtitles are #102's; its formats carry none.
        subtitles: list[SubtitleTrack] = []
        files: dict[int, tuple[Sidecar, SidecarSource]] = {}
        if page is not None:
            tracks, track = page.audio_tracks, page.audio_track
            subtitles, files = with_sidecars([], page.subtitles)
        else:
            tracks = list(result.audio_tracks)
            track = pick_audio_track(tracks, audio_language, audio_track)
            subtitles = list(result.subtitle_tracks)
        return self._new_session(
            inputs,
            duration,
            has_video,
            origin=page.origin if page is not None else None,
            playlists=playlists,
            audio_tracks=tracks,
            audio_track=track,
            site_audio=page.site_audio if page is not None else None,
            subtitle_tracks=subtitles,
            subtitle_files=files,
        )

    async def _open_page(
        self, url: str, audio_language: str | None = None, audio_track: int | None = None
    ) -> _Page | None:
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
        plan = playback_plan(info.formats, audio_language)
        # Every format came back resolved, so a track can be swapped in
        # without asking the site again.
        choices = [choice for choice in audio_choices(info.formats, plan) if choice.id in resolved]
        if audio_track is not None and 0 <= audio_track < len(choices):
            plan = replace(plan, audio=choices[audio_track])
        parts = [part for part in (plan.video, plan.audio) if part is not None]
        playing = plan.audio.id if plan.audio is not None else None
        return _Page(
            inputs=[MediaInput(resolved[part.id].url, resolved[part.id].headers) for part in parts],
            origin=SiteOrigin(info.webpage_url or url, tuple(part.id for part in parts)),
            hls=all(part.hls for part in parts),
            has_video=plan.video is not None,
            audio_tracks=[
                AudioTrack(
                    index=n,
                    language=choice.language,
                    channels=choice.audio_channels,
                    codec=choice.acodec,
                    default=n == 0,
                )
                for n, choice in enumerate(choices)
            ],
            site_audio=[
                (choice.id, MediaInput(resolved[choice.id].url, resolved[choice.id].headers)) for choice in choices
            ],
            audio_track=next((n for n, choice in enumerate(choices) if choice.id == playing), None),
            subtitles=[_site_subtitle(subtitle) for subtitle in info.subtitles],
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

    async def start_torrent_session(
        self,
        torrent_raw: str,
        file_index: int | None = None,
        *,
        audio_language: str | None = None,
        audio_track: int | None = None,
    ) -> StreamSession:
        """Stream a torrent's file: ``file_index``, else its largest media file (#98)."""
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
        if file_index is None:
            target = pick_media_file(details.files)
            refusal = "This torrent has no playable media file"
        else:
            target = next(
                (
                    file
                    for file in details.files
                    if file.index == file_index and file.path.lower().endswith(MEDIA_EXTENSIONS)
                ),
                None,
            )
            refusal = f"This torrent has no media file at index {file_index}"
        if target is None:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message=refusal,
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )

        # A folder of its own, so a streamed file can't overwrite a download's
        # (#107). A torrent a task already has keeps its own: rqbit ignores a
        # re-add's options (#93).
        # Its subtitle files come too: they're small, and it's what they're for (#101).
        by_path = {file.path: file.index for file in details.files}
        sidecars = match_sidecars(target.path, list(by_path))
        await self._torrent_client.add(
            source,
            only_files=[target.index, *(by_path[sidecar.path] for sidecar in sidecars)],
            output_folder=str(torrent_folder(self._torrent_dir, details.name, details.info_hash)),
        )
        return self._torrent_stream_session(
            details.info_hash,
            target.index,
            audio_language=audio_language,
            audio_track=audio_track,
            sidecars=[(sidecar, TorrentFile(details.info_hash, by_path[sidecar.path])) for sidecar in sidecars],
        )

    def _torrent_stream_session(
        self,
        info_hash: str,
        file_index: int,
        *,
        audio_language: str | None = None,
        audio_track: int | None = None,
        sidecars: list[tuple[Sidecar, SidecarSource]] | None = None,
    ) -> StreamSession:
        """A session reading one file of a torrent rqbit has, through its stream endpoint.

        rqbit fetches the pieces being read first, so it plays while the
        torrent downloads. The session starts ``connecting`` and is probed in
        the background, publishing the swarm's numbers meanwhile.
        """
        stream_url = f"{self._torrent_api_url}/torrents/{info_hash}/stream/{file_index}"
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
            info_hash=info_hash,
            status="connecting",
            audio_track=audio_track,
            audio_language=audio_language,
            pending_sidecars=sidecars or [],
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
        session.audio_tracks = list(result.audio_tracks)
        session.subtitle_tracks, session.subtitle_files = with_sidecars(
            result.subtitle_tracks, session.pending_sidecars
        )
        session.audio_track = pick_audio_track(result.audio_tracks, session.audio_language, session.audio_track)
        session.status = "ready"
        self._publish_status(
            session,
            status="ready",
            audio_tracks=[asdict(track) for track in session.audio_tracks],
            audio_track=session.audio_track,
            subtitle_tracks=[{**asdict(track), "text": track.text} for track in session.subtitle_tracks],
        )
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
        async def encode() -> None:
            async with session.encode_semaphore:
                await self._encode(session, index)

        await _produce_once(session.states, session.events, index, encode)

    async def get_subtitle_segment(self, session: StreamSession, track: int, index: int) -> Path:
        """Segment ``index``'s cues for subtitle track ``track``, as WebVTT (#100).

        The first request for a segment cuts every text track's cues at once;
        the rest are read from disk.
        """
        if index < 0 or index >= session.segment_count:
            raise Error.not_found(f"Segment {index} does not exist")
        texts = [known for known in session.subtitle_tracks if known.text and not known.external]
        if track not in [known.index for known in texts]:
            raise Error.not_found(f"This stream has no subtitle track {track} to show")
        session.touch()

        async def cut() -> None:
            if not self._is_open(session):
                raise _session_not_found()
            start = index * session.segment_seconds
            await self._encoder(
                subtitle_args(
                    self._ffmpeg_path,
                    session.inputs[0],
                    start,
                    session.segment_duration(index),
                    [(known.index, session.cue_path(index, known.index)) for known in texts],
                )
            )

        try:
            await _produce_once(session.cue_states, session.cue_events, index, cut)
        except (Error, OSError) as error:
            if self._is_open(session):
                raise
            raise _session_not_found() from error
        if not self._is_open(session):
            raise _session_not_found()
        return session.cue_path(index, track)

    async def subtitle_file(self, task_id: uuid.UUID, file_index: int | None, track: int) -> Path:
        """A finished task's subtitle track, whole, as WebVTT, for a file played as it is (#100).

        An embedded track is extracted; a subtitle file beside it (#101) is
        converted. Either way once, and kept under the stream folder, keyed
        by the video's size and time, so a file replaced on disk is done again.
        """
        path, _filename, index = await self._task_file(task_id, file_index)
        stat = await asyncio.to_thread(path.stat)
        cached = self._stream_dir / "subtitles" / f"{task_id}-{index or 0}-{track}-{stat.st_size}-{stat.st_mtime_ns}.vtt"
        if await asyncio.to_thread(cached.exists):
            return cached
        result = await self._prober(self._ffprobe_path, str(path))
        tracks, files = with_sidecars(result.subtitle_tracks, await self._sidecars_of(task_id, index))
        if track not in [known.index for known in tracks if known.text]:
            raise Error.not_found(f"This file has no subtitle track {track} to show")
        await asyncio.to_thread(cached.parent.mkdir, parents=True, exist_ok=True)
        if track in files:
            await self._convert_sidecar(*files[track], cached)
        else:
            await self._write_aside(
                cached,
                lambda partial: self._encoder(
                    subtitle_file_args(self._ffmpeg_path, MediaInput(str(path)), track, partial)
                ),
            )
        return cached

    async def get_subtitle_file(self, session: StreamSession, track: int) -> Path:
        """A session's subtitle file (#101), whole, as WebVTT: converted on the first request."""
        if track not in session.subtitle_files:
            raise Error.not_found(f"This stream has no subtitle file {track}")
        session.touch()
        destination = session.subtitle_file_path(track)

        async def convert() -> None:
            if not self._is_open(session):
                raise _session_not_found()
            try:
                await self._convert_sidecar(*session.subtitle_files[track], destination)
            except Error as error:
                # A site's subtitle URL expires like its media's (#102): ask
                # the site again, once, and find the same track.
                source = session.subtitle_files[track][1]
                if error.code != Code.FORBIDDEN or not isinstance(source, SiteSubtitleFile):
                    raise
                await self._refresh_site_subtitle(session, track, source)
                await self._convert_sidecar(*session.subtitle_files[track], destination)

        try:
            await _produce_once(session.file_states, session.file_events, track, convert)
        except (Error, OSError) as error:
            if self._is_open(session):
                raise
            raise _session_not_found() from error
        if not self._is_open(session):
            raise _session_not_found()
        return destination

    async def _refresh_site_subtitle(self, session: StreamSession, track: int, stale: SiteSubtitleFile) -> None:
        if session.origin is None or self._site_client is None:
            raise site_error.media_forbidden("The site refused its subtitles")
        info, _ = await self._site_client.open(session.origin.page_url)
        wanted = SiteSubtitle(stale.language, "", stale.automatic, "", "")
        fresh = next((subtitle for subtitle in info.subtitles if same_subtitle(subtitle, wanted)), None)
        if fresh is None:
            raise Error.not_found("The site no longer offers those subtitles")
        session.subtitle_files[track] = _site_subtitle(fresh)

    async def _convert_sidecar(self, sidecar: Sidecar, source: SidecarSource, destination: Path) -> None:
        """A subtitle file as WebVTT at ``destination``: read, decoded, written as UTF-8, then converted.

        Decoded here rather than by ffmpeg, which reads everything as UTF-8
        and garbles a Windows-1252 file's accents.
        """
        if isinstance(source, TorrentFile):
            raw = await self._fetch_bytes(f"{self._torrent_api_url}/torrents/{source.info_hash}/stream/{source.index}")
        elif isinstance(source, SiteSubtitleFile):
            raw = await self._fetch_subtitle(source.url, dict(source.headers))
        else:
            raw = await asyncio.to_thread(source.read_bytes)
        text = decode_subtitles(raw)
        suffix = Path(sidecar.path).suffix.lower() or ".srt"
        utf8 = destination.with_name(f"{destination.stem}.{uuid.uuid4().hex}{suffix}")
        await asyncio.to_thread(utf8.write_text, text, encoding="utf-8")
        try:
            await self._write_aside(
                destination,
                lambda partial: self._encoder(
                    subtitle_file_args(self._ffmpeg_path, MediaInput(str(utf8)), 0, partial)
                ),
            )
        finally:
            await asyncio.to_thread(utf8.unlink, missing_ok=True)

    @staticmethod
    async def _write_aside(destination: Path, write: Callable[[Path], Awaitable[None]]) -> None:
        """Have ``write`` fill a file beside ``destination``, then move it into place.

        Two players asking at once must never read half a file.
        """
        partial = destination.with_name(f"{destination.stem}.{uuid.uuid4().hex}.part.vtt")
        try:
            await write(partial)
            await asyncio.to_thread(os.replace, partial, destination)
        finally:
            await asyncio.to_thread(partial.unlink, missing_ok=True)

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
            audio_track=session.mapped_audio_track,
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

    async def switch_audio(self, session: StreamSession, track: int) -> StreamSession:
        """A new session like ``session``, playing audio track ``track`` (#99).

        Built from what ``session`` already holds, never asking a site or rqbit
        again: its inputs, its playlists, its torrent. A site's track is its
        audio format, so the last input changes, and for HLS its playlist.
        ``session`` keeps playing; the player stops it once the new one has
        caught up.
        """
        if session.status != "ready":
            raise Error.conflict("The stream is not ready yet")
        if track not in [known.index for known in session.audio_tracks]:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message=f"This stream has no audio track {track}",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )
        inputs, origin, playlists = list(session.inputs), session.origin, list(session.playlists)
        if session.site_audio:
            format_id, source = session.site_audio[track]
            inputs[-1] = source
            if origin is not None:
                origin = SiteOrigin(origin.page_url, (*origin.format_ids[:-1], format_id))
            if playlists:
                playlists[-1] = await self._fetch_playlist(source)
        switched = self._new_session(
            inputs,
            session.duration_seconds,
            session.has_video,
            origin=origin,
            playlists=playlists,
            subtitle_tracks=list(session.subtitle_tracks),
            subtitle_files=dict(session.subtitle_files),
            audio_tracks=list(session.audio_tracks),
            audio_track=track,
            site_audio=list(session.site_audio),
            info_hash=session.info_hash,
        )
        if switched.info_hash is not None and self._torrent_client is not None:
            switched.progress_task = asyncio.create_task(self._publish_progress_until_cancelled(switched))
        return switched

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

        # A switch leaves two sessions on one torrent for a moment (#99): the
        # one still playing needs it.
        shared = any(other.info_hash == session.info_hash for other in self._sessions.all())
        if session.info_hash and not shared and self._torrent_client is not None and self._task_repo is not None:
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
