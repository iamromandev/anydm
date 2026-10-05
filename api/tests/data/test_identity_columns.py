"""Every download names what it is: relations to its provider and address rows."""

from typing import Any

import pytest
from src.data.db import model
from src.data.db.model import Download, SiteDetail
from src.data.type import DownloadStatus, MediaKind, Preset
from tortoise.exceptions import IntegrityError


async def a_download(**fields: Any) -> Download:
    base: dict[str, Any] = {"status": DownloadStatus.PENDING}
    base.update(fields)
    return await Download.create(**base)


@pytest.mark.asyncio
async def test_a_site_detail_keeps_only_what_the_site_alone_has(sqlite: None) -> None:
    row = await a_download()

    detail = await SiteDetail.create(download=row, preset=Preset.P1080, video_format="137", audio_format="140")

    assert (detail.preset, detail.video_format, detail.audio_format) == (Preset.P1080, "137", "140")
    assert "extractor" not in SiteDetail._meta.fields_map
    assert "video_id" not in SiteDetail._meta.fields_map


@pytest.mark.asyncio
async def test_a_site_detail_names_the_title_and_kind_a_download_no_longer_stores(sqlite: None) -> None:
    row = await a_download()

    detail = await SiteDetail.create(download=row, preset=Preset.BEST)

    # A bare video until the extract says otherwise; it has no place in a playlist.
    assert (detail.title, detail.media_kind, detail.playlist_index) == ("", MediaKind.VIDEO, None)


@pytest.mark.asyncio
async def test_a_collection_orders_its_videos_by_their_place_in_the_playlist(sqlite: None) -> None:
    playlist = await a_download()
    await SiteDetail.create(download=playlist, preset=Preset.P720, media_kind=MediaKind.PLAYLIST, title="Talks")
    # Added in one bulk insert, so created_at cannot tell them apart; playlist_index can.
    for index in (3, 1, 2):
        await SiteDetail.create(download=await a_download(parent=playlist), preset=Preset.P720, playlist_index=index)

    members = await SiteDetail.filter(download__parent=playlist).order_by("playlist_index")

    assert [member.playlist_index for member in members] == [1, 2, 3]


@pytest.mark.asyncio
async def test_a_download_has_at_most_one_site_detail(sqlite: None) -> None:
    row = await a_download()
    await SiteDetail.create(download=row, preset=Preset.BEST)

    with pytest.raises(IntegrityError):
        await SiteDetail.create(download=row, preset=Preset.BEST)


def test_a_torrent_has_no_detail_table() -> None:
    assert not hasattr(model, "TorrentDetail")
