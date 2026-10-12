"""Stand-ins for a download loaded with ``RELATED``: what ``describe`` reads off it."""

import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from src.data.type import DOWNLOADS_ID, MirrorStatus, SourceKind


def a_mirror(url: str, kind: SourceKind, provider: str, *, priority: int = 0) -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid.uuid4(),
        status=MirrorStatus.AVAILABLE,
        priority=priority,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
        source=SimpleNamespace(
            url=SimpleNamespace(value=url),
            kind=kind,
            provider=SimpleNamespace(name=provider),
        ),
    )


def a_torrent(info_hash: str, name: str = "T") -> SimpleNamespace:
    return SimpleNamespace(info_hash=info_hash, name=name)


def a_site_row(url: str, *, provider: str, title: str, kind: Any, preset: Any, **fields: Any) -> SimpleNamespace:
    """A site download or collection container as a read would load it."""
    fields.setdefault("category", SimpleNamespace(id=DOWNLOADS_ID, name="Downloads", folder=""))
    fields.setdefault("parent_id", None)
    return SimpleNamespace(
        mirrors=[a_mirror(url, SourceKind.SITE, provider)],
        torrent=None,
        media=SimpleNamespace(
            title=title, kind=kind, preset=preset, video_format=None, audio_format=None, playlist_index=None
        ),
        **fields,
    )
