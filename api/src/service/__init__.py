from functools import lru_cache
from pathlib import Path

import httpx

from src.config import get_settings
from src.data.repo import TaskDatabaseRepo, TaskSegmentDatabaseRepo
from src.lib.event import get_event_hub
from src.lib.youtube.client import get_youtube_client
from src.service.download import DownloadService as DownloadService
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker, WorkerPool
from src.service.download.downloader import Downloader
from src.service.download.post_process import FfmpegPostProcessor
from src.service.download.segmented import SegmentedDownloader
from src.service.extract import ExtractService as ExtractService
from src.service.health import HealthService as HealthService


def get_health_service() -> HealthService:
    return HealthService()


def get_extract_service() -> ExtractService:
    return ExtractService(client=get_youtube_client())


@lru_cache
def get_download_control() -> DownloadControl:
    return DownloadControl()


def get_download_service() -> DownloadService:
    settings = get_settings()
    return DownloadService(
        repo=TaskDatabaseRepo(),
        segment_repo=TaskSegmentDatabaseRepo(),
        client=get_youtube_client(),
        control=get_download_control(),
        hub=get_event_hub(),
        downloads_root=Path(settings.download_dir),
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
    downloader = Downloader(
        http_client,
        chunk_size=settings.download_chunk_size,
        flush_interval_ms=settings.download_progress_flush_ms,
        write_buffer_bytes=settings.download_write_buffer_bytes,
    )
    engine = SegmentedDownloader(
        http_client,
        downloader,
        chunk_size=settings.download_chunk_size,
        flush_interval_ms=settings.download_progress_flush_ms,
        min_segment_bytes=settings.download_segment_min_bytes,
        write_buffer_bytes=settings.download_write_buffer_bytes,
    )
    workers = [
        DownloadWorker(
            name=f"worker-{index}",
            repo=TaskDatabaseRepo(),
            segment_repo=TaskSegmentDatabaseRepo(),
            client=get_youtube_client(),
            engine=engine,
            post_processor=FfmpegPostProcessor(settings.ffmpeg_path),
            control=get_download_control(),
            hub=get_event_hub(),
            downloads_root=Path(settings.download_dir),
            max_attempts=settings.download_max_attempts,
            segments=settings.download_segments,
        )
        for index in range(settings.download_workers)
    ]
    return WorkerPool(workers, http_client)
