"""Rows for integration tests, created straight through the models, the way the add paths lay them out.

A download is its row plus a mirror onto a source (provider, address, kind);
a site download adds its media, a torrent its ``torrent.Torrent``.
"""

from typing import Any

from src.core.url import normalize_url, url_hash
from src.data.db.model import Download, File, Media, Mirror, Provider, Source, Torrent, Url
from src.data.type import DownloadStatus, MediaKind, Preset, SourceKind


async def a_source(provider: str, address: str, kind: SourceKind) -> Source:
    owner, _ = await Provider.get_or_create(slug=provider.lower(), defaults={"name": provider})
    normalized = normalize_url(address)
    url, _ = await Url.get_or_create(
        normalized_hash=url_hash(normalized),
        defaults={"value": address, "normalized": normalized, "scheme": address.split(":", 1)[0]},
    )
    source, _ = await Source.get_or_create(provider=owner, url=url, kind=kind)
    return source


async def a_download(
    address: str = "https://example.com/a.bin",
    *,
    provider: str = "http",
    kind: SourceKind = SourceKind.DIRECT,
    filename: str | None = None,
    **fields: Any,
) -> Download:
    """A direct download unless told otherwise, with its file when ``filename`` is given."""
    fields.setdefault("status", DownloadStatus.PENDING)
    row = await Download.create(**fields)
    await Mirror.create(download=row, source=await a_source(provider, address, kind))
    if filename is not None:
        await File.create(download=row, filename=filename, path=filename, index=0)
    return row


async def a_site_download(
    video_id: str, *, title: str = "", playlist_index: int | None = None, **fields: Any
) -> Download:
    row = await a_download(
        f"https://www.youtube.com/watch?v={video_id}", provider="Youtube", kind=SourceKind.CONTENT, **fields
    )
    await Media.create(download=row, title=title, preset=Preset.P1080, playlist_index=playlist_index)
    return row


async def a_torrent_download(info_hash: str, *, name: str, **fields: Any) -> Download:
    row = await a_download(f"magnet:?xt=urn:btih:{info_hash}", provider="torrent", kind=SourceKind.TORRENT, **fields)
    source = (await Mirror.get(download=row).prefetch_related("source")).source
    await Torrent.create(source=source, name=name, info_hash=info_hash)
    return row


async def a_collection(playlist_id: str = "PL1", *, title: str = "Talks", **fields: Any) -> Download:
    row = await a_download(
        f"https://www.youtube.com/playlist?list={playlist_id}", provider="Youtube", kind=SourceKind.CONTENT, **fields
    )
    await Media.create(download=row, title=title, kind=MediaKind.PLAYLIST, preset=Preset.P720)
    return row
