"""Rows for integration tests, created straight through the models."""

import uuid
from typing import Any

from src.data.db.model import Download
from src.data.type import DownloadStatus, MediaKind, Platform


async def a_download(**overrides: Any) -> Download:
    fields: dict[str, Any] = {
        "source_url": "https://example.com/a.bin",
        "provider": "http",
        "ref_id": uuid.uuid4().hex,
        "platform": Platform.DIRECT,
        "media_kind": MediaKind.FILE,
        "status": DownloadStatus.PENDING,
    }
    fields.update(overrides)
    return await Download.create(**fields)
