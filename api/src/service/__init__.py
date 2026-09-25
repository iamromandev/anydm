from functools import lru_cache, partial
from pathlib import Path

import httpx

from src.config import get_settings
from src.data.repo import FileDatabaseRepo, SegmentDatabaseRepo, TaskDatabaseRepo
from src.lib.event import get_event_hub
from src.lib.media.ffprobe import probe
from src.lib.site.client import get_site_client
from src.lib.torrent.client import RqbitClient
from src.service.download import DownloadService as DownloadService
from src.service.download import TorrentService as TorrentService
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard as DiskGuard
from src.service.download.disk_monitor import DiskMonitor
from src.service.download.download_worker import DownloadWorker, WorkerPool
from src.service.download.downloader import Downloader
from src.service.download.fragment import FragmentDownloader, fragment_limits
from src.service.download.post_process import FfmpegPostProcessor
from src.service.download.rate_limit import rate_limiter
from src.service.download.segmented import SegmentedDownloader
from src.service.download.torrent_monitor import TorrentMonitor
from src.service.extract import ExtractService as ExtractService
from src.service.health import HealthService as HealthService
from src.service.settings import SettingsService as SettingsService
from src.service.stream import StreamIdleSweeper as StreamIdleSweeper
from src.service.stream import StreamService as StreamService
from src.service.stream import StreamSessionStore as StreamSessionStore
from src.service.stream import TorrentReaper as TorrentReaper


def get_health_service() -> HealthService:
    return HealthService()


def get_settings_service() -> SettingsService:
    return SettingsService()


def get_extract_service() -> ExtractService:
    return ExtractService(client=get_site_client())


@lru_cache
def get_download_control() -> DownloadControl:
    return DownloadControl()


@lru_cache
def get_disk_guard() -> DiskGuard:
    settings = get_settings()
    return DiskGuard(settings.download_dir, settings.download_min_free_bytes)


@lru_cache
def get_disk_monitor() -> DiskMonitor:
    """One monitor per process, because it is a singleton background loop."""
    return DiskMonitor(get_disk_guard(), get_event_hub())


def get_download_service() -> DownloadService:
    settings = get_settings()
    return DownloadService(
        repo=TaskDatabaseRepo(),
        segment_repo=SegmentDatabaseRepo(),
        client=get_site_client(),
        control=get_download_control(),
        hub=get_event_hub(),
        downloads_root=Path(settings.download_dir),
        torrents=get_torrent_service(),
        disk=get_disk_guard(),
    )


@lru_cache
def get_torrent_client() -> RqbitClient:
    """One client, one connection pool, for the life of the process.

    The short timeout here is for control calls. Adding a torrent overrides it
    with the metadata timeout, because waiting for a peer is not the same kind
    of wait as asking the engine to pause something.
    """
    settings = get_settings()
    return RqbitClient(
        settings.torrent_api_url,
        client=httpx.AsyncClient(timeout=httpx.Timeout(settings.torrent_request_timeout_s)),
        metadata_timeout_s=settings.torrent_metadata_timeout_s,
    )


async def close_torrent_client() -> None:
    await get_torrent_client().aclose()


def get_torrent_service() -> TorrentService:
    settings = get_settings()
    return TorrentService(
        repo=TaskDatabaseRepo(),
        file_repo=FileDatabaseRepo(),
        client=get_torrent_client(),
        hub=get_event_hub(),
        torrent_root=Path(settings.torrent_dir).resolve(),
        enabled=settings.torrent_enabled,
        disk=get_disk_guard(),
    )


@lru_cache
def get_torrent_monitor() -> TorrentMonitor:
    """One monitor per process, because it is a singleton background loop."""
    settings = get_settings()
    return TorrentMonitor(
        repo=TaskDatabaseRepo(),
        file_repo=FileDatabaseRepo(),
        client=get_torrent_client(),
        hub=get_event_hub(),
        poll_ms=settings.torrent_poll_ms,
        torrent_root=str(Path(settings.torrent_dir).resolve()),
        enabled=settings.torrent_enabled,
        download_limit_bps=settings.torrent_download_limit_bps,
        upload_limit_bps=settings.torrent_upload_limit_bps,
    )


def build_worker_pool() -> WorkerPool:
    """One HTTP client shared by every worker, closed when the pool stops.

    ``read=None`` disables the read timeout: a large file legitimately takes
    minutes, and httpx's default would abort it mid-transfer.

    The pool limits are derived from the worker and segment counts rather than
    configured, so they cannot drift below the number of sockets the workers can
    actually open — a pool that is too small does not error, it just serialises
    the segments and hides the speedup.
    """
    settings = get_settings()
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(30.0, read=None),
        http2=True,
        limits=httpx.Limits(
            max_connections=settings.http_max_connections,
            max_keepalive_connections=settings.http_max_keepalive,
        ),
    )
    # One limiter for the whole pool: the cap is on the connection, not per task.
    limiter = rate_limiter(settings.download_rate_limit_bps)
    downloader = Downloader(
        http_client,
        chunk_size=settings.download_chunk_size,
        flush_interval_ms=settings.download_progress_flush_ms,
        write_buffer_bytes=settings.download_write_buffer_bytes,
        limiter=limiter,
    )
    engine = SegmentedDownloader(
        http_client,
        downloader,
        chunk_size=settings.download_chunk_size,
        flush_interval_ms=settings.download_progress_flush_ms,
        min_segment_bytes=settings.download_segment_min_bytes,
        write_buffer_bytes=settings.download_write_buffer_bytes,
        limiter=limiter,
    )
    # yt-dlp's reads cannot pass through ``limiter``, so the fragment path gets
    # a share of the cap of its own, and charges what it reads to ``limiter``
    # after the fact.
    concurrency, fragment_rate_bps = fragment_limits(
        settings.download_rate_limit_bps, settings.download_workers, settings.download_segments
    )
    fragments = FragmentDownloader(
        get_site_client(),
        concurrency=concurrency,
        rate_bps=fragment_rate_bps,
        limiter=limiter,
        poll_s=settings.download_progress_flush_ms / 1000,
    )
    workers = [
        DownloadWorker(
            name=f"worker-{index}",
            repo=TaskDatabaseRepo(),
            segment_repo=SegmentDatabaseRepo(),
            client=get_site_client(),
            engine=engine,
            post_processor=FfmpegPostProcessor(settings.ffmpeg_path),
            control=get_download_control(),
            hub=get_event_hub(),
            downloads_root=Path(settings.download_dir),
            max_attempts=settings.download_max_attempts,
            segments=settings.download_segments,
            disk=get_disk_guard(),
            fragments=fragments,
        )
        for index in range(settings.download_workers)
    ]
    return WorkerPool(workers, http_client)


@lru_cache
def get_stream_sessions() -> StreamSessionStore:
    return StreamSessionStore()


def get_stream_service() -> StreamService:
    settings = get_settings()
    return StreamService(
        sessions=get_stream_sessions(),
        stream_dir=Path(settings.stream_dir),
        ffmpeg_path=settings.ffmpeg_path,
        ffprobe_path=settings.ffprobe_path,
        segment_seconds=settings.stream_segment_seconds,
        readahead_segments=settings.stream_readahead_segments,
        max_concurrent_encodes=settings.stream_max_concurrent_encodes,
        prober=partial(probe, timeout_s=settings.stream_probe_timeout_s),
        torrent_client=get_torrent_client(),
        task_repo=TaskDatabaseRepo(),
        torrent_dir=Path(settings.torrent_dir).resolve(),
        torrent_api_url=settings.torrent_api_url,
        torrent_enabled=settings.torrent_enabled,
        event_hub=get_event_hub(),
        site_client=get_site_client(),
        task_files=get_download_service().resolve_media_file,
        torrent_play=get_download_service().torrent_play,
    )


@lru_cache
def get_stream_sweeper() -> StreamIdleSweeper:
    """One sweeper per process, because it is a singleton background loop."""
    settings = get_settings()
    return StreamIdleSweeper(
        service=get_stream_service(),
        sessions=get_stream_sessions(),
        idle_timeout_s=settings.stream_idle_timeout_s,
    )


@lru_cache
def get_torrent_reaper() -> TorrentReaper:
    """One reaper per process, because it is a singleton background loop."""
    settings = get_settings()
    return TorrentReaper(
        client=get_torrent_client(),
        task_repo=TaskDatabaseRepo(),
        sessions=get_stream_sessions(),
        poll_s=settings.torrent_reap_poll_s,
    )
