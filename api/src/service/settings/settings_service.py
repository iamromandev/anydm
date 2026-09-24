"""Reporting the configuration, one named field at a time."""

from __future__ import annotations

from src.config import get_settings
from src.core.base import BaseService
from src.core.common import get_app_version
from src.data.schema.settings import ServerSettingsSchema
from src.lib.site.client import ytdlp_version


class SettingsService(BaseService):
    def describe(self) -> ServerSettingsSchema:
        """The subset of settings worth showing, listed by hand.

        Written out rather than derived so that adding a setting never adds it
        to this response by accident. Anything secret is absent because it was
        never named, not because a filter caught it.
        """
        settings = get_settings()

        return ServerSettingsSchema(
            version=get_app_version(),
            yt_dlp_version=ytdlp_version(),
            env=str(settings.env.value if hasattr(settings.env, "value") else settings.env),
            download_dir=settings.download_dir,
            download_workers=settings.download_workers,
            download_segments=settings.download_segments,
            download_max_attempts=settings.download_max_attempts,
            download_rate_limit_bps=settings.download_rate_limit_bps,
            download_min_free_bytes=settings.download_min_free_bytes,
            torrent_enabled=settings.torrent_enabled,
            torrent_dir=settings.torrent_dir,
            torrent_download_limit_bps=settings.torrent_download_limit_bps,
            torrent_upload_limit_bps=settings.torrent_upload_limit_bps,
            stream_segment_seconds=settings.stream_segment_seconds,
            stream_max_concurrent_encodes=settings.stream_max_concurrent_encodes,
            ffmpeg_path=settings.ffmpeg_path,
        )
