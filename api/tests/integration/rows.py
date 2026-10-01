"""Rows for integration tests, created straight through the models."""

from src.data.db.model import Download, Queue
from src.data.type import DownloadStatus, MediaKind, Platform


async def a_download(**overrides: object) -> Download:
    fields: dict[str, object] = {
        "source_url": "https://example.com/a.bin",
        "platform": Platform.DIRECT,
        "media_kind": MediaKind.FILE,
        "status": DownloadStatus.PENDING,
        "queue": await Queue.get(name="Main"),
    }
    fields.update(overrides)
    return await Download.create(**fields)
