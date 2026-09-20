"""What the API is willing to say about how it is configured."""

from __future__ import annotations

from src.core.base import BaseSchema


class ServerSettingsSchema(BaseSchema):
    """The configuration a person can see, and nothing else.

    Every field here is named deliberately. Nothing walks ``Settings`` and
    publishes what it finds: that pattern grows a new field every time the
    configuration does, which is how a password ends up on a web page.
    """

    version: str = ""
    env: str = ""
    download_dir: str = ""
    download_workers: int = 0
    download_segments: int = 0
    download_max_attempts: int = 0
    torrent_enabled: bool = False
    torrent_dir: str = ""
    stream_segment_seconds: int = 0
    stream_max_concurrent_encodes: int = 0
    ffmpeg_path: str = ""
