"""What a download is comes from the rows it was added from, not from columns on it."""

from typing import Any

import pytest
from src.core.url import normalize_url, url_hash
from src.data.db.model import Download, File, Media, Mirror, Provider, Source, Torrent, Url
from src.data.repo.download.described import describe
from src.data.repo.download.interface.download import NOT_CONTAINER, RELATED
from src.data.type import MediaKind, Platform, Preset, SourceKind
from src.lib.identity import url_ref


async def a_source(provider: str, address: str, kind: SourceKind) -> Source:
    owner, _ = await Provider.get_or_create(slug=provider.lower(), defaults={"name": provider})
    normalized = normalize_url(address)
    url = await Url.create(value=address, normalized=normalized, normalized_hash=url_hash(normalized), scheme="https")
    return await Source.create(provider=owner, url=url, kind=kind)


async def a_download(provider: str, address: str, kind: SourceKind, *, priority: int = 0, **fields: Any) -> Download:
    row = await Download.create(**fields)
    await Mirror.create(download=row, source=await a_source(provider, address, kind), priority=priority)
    return row


async def loaded(row: Download) -> Download:
    return await Download.get(id=row.id).prefetch_related(*RELATED)


@pytest.mark.asyncio
async def test_a_site_download_is_its_media_and_its_page(sqlite: None) -> None:
    row = await a_download("Youtube", "https://youtu.be/dQw4w9WgXcQ", SourceKind.CONTENT)
    await Media.create(download=row, title="Never", kind=MediaKind.VIDEO, preset=Preset.P1080)

    described = describe(await loaded(row))

    assert described.platform == Platform.SITE
    assert (described.provider, described.ref) == ("Youtube", "dQw4w9WgXcQ")
    assert (described.title, described.media_kind) == ("Never", MediaKind.VIDEO)
    assert described.url == "https://youtu.be/dQw4w9WgXcQ"
    assert described.media.preset == Preset.P1080 and described.info_hash is None


@pytest.mark.asyncio
async def test_a_playlist_is_named_by_its_listing_id(sqlite: None) -> None:
    # Stored under the site's name; its address is the tab extractor's.
    row = await a_download("Youtube", "https://www.youtube.com/playlist?list=PL123", SourceKind.CONTENT)
    await Media.create(download=row, title="Talks", kind=MediaKind.PLAYLIST, preset=Preset.BEST)

    assert describe(await loaded(row)).ref == "PL123"


@pytest.mark.asyncio
async def test_a_torrent_is_named_by_its_info_hash(sqlite: None) -> None:
    row = await a_download("torrent", "magnet:?xt=urn:btih:" + "a" * 40, SourceKind.TORRENT)
    source = (await Mirror.get(download=row).prefetch_related("source")).source
    await Torrent.create(source=source, name="Big Buck Bunny", info_hash="a" * 40)

    described = describe(await loaded(row))

    assert (described.platform, described.media_kind) == (Platform.TORRENT, MediaKind.FILE)
    assert (described.ref, described.info_hash, described.title) == ("a" * 40, "a" * 40, "Big Buck Bunny")
    assert described.media is None


@pytest.mark.asyncio
async def test_a_direct_file_is_named_by_its_file_or_its_address(sqlite: None) -> None:
    address = "https://example.com/files/ubuntu.iso?token=1"
    row = await a_download("http", address, SourceKind.DIRECT)
    file = await File.create(download=row, filename="ubuntu-24.04.iso", path="ubuntu-24.04.iso", index=0)

    with_file = describe(await loaded(row), [file])
    without = describe(await loaded(row))

    assert (with_file.platform, with_file.ref) == (Platform.DIRECT, url_ref(address))
    assert (with_file.title, without.title) == ("ubuntu-24.04.iso", "ubuntu.iso")


@pytest.mark.asyncio
async def test_the_primary_mirror_is_the_lowest_priority(sqlite: None) -> None:
    row = await a_download("http", "https://b.example/x.bin", SourceKind.DIRECT, priority=1)
    await Mirror.create(download=row, source=await a_source("http", "https://a.example/x.bin", SourceKind.DIRECT))

    assert describe(await loaded(row)).url == "https://a.example/x.bin"


@pytest.mark.asyncio
async def test_containers_are_told_apart_by_their_media(sqlite: None) -> None:
    direct = await Download.create()
    video = await Download.create()
    playlist = await Download.create()
    await Media.create(download=video, preset=Preset.BEST)
    await Media.create(download=playlist, preset=Preset.BEST, kind=MediaKind.PLAYLIST)

    assert {row.id for row in await Download.filter(NOT_CONTAINER)} == {direct.id, video.id}


@pytest.mark.asyncio
async def test_a_collection_lists_its_videos_in_listing_order(sqlite: None) -> None:
    playlist = await Download.create()
    second, first = await Download.create(parent=playlist), await Download.create(parent=playlist)
    await Media.create(download=second, preset=Preset.BEST, playlist_index=2)
    await Media.create(download=first, preset=Preset.BEST, playlist_index=1)

    members = await Download.filter(parent=playlist).order_by("media__playlist_index", "created_at")

    assert [row.id for row in members] == [first.id, second.id]
