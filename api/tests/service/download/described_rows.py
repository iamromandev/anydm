"""Stand-ins for a download loaded with ``RELATED``: what ``describe`` reads off it."""

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from src.data.type import SourceKind


def a_mirror(url: str, kind: SourceKind, provider: str, *, torrents: Any = (), priority: int = 0) -> SimpleNamespace:
    return SimpleNamespace(
        priority=priority,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
        source=SimpleNamespace(
            url=SimpleNamespace(value=url),
            kind=kind,
            provider=SimpleNamespace(name=provider),
            torrents=list(torrents),
        ),
    )


def a_torrent(info_hash: str, name: str = "T") -> SimpleNamespace:
    return SimpleNamespace(info_hash=info_hash, name=name)


def a_site_row(url: str, *, provider: str, title: str, kind: Any, preset: Any, **fields: Any) -> SimpleNamespace:
    """A site download or collection container as a read would load it."""
    return SimpleNamespace(
        mirrors=[a_mirror(url, SourceKind.CONTENT, provider)],
        media=SimpleNamespace(
            title=title, kind=kind, preset=preset, video_format=None, audio_format=None, playlist_index=None
        ),
        **fields,
    )
