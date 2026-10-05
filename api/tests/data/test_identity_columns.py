"""Every download names what it is: relations to its provider and address rows."""

from typing import Any

import pytest
from src.data.db import model
from src.data.db.model import Download, Media
from src.data.type import DownloadStatus, MediaKind, Preset
from tortoise.exceptions import IntegrityError


async def a_download(**fields: Any) -> Download:
    base: dict[str, Any] = {"status": DownloadStatus.PENDING}
    base.update(fields)
    return await Download.create(**base)


@pytest.mark.asyncio
async def test_media_keeps_only_what_the_site_alone_has(sqlite: None) -> None:
    row = await a_download()

    media = await Media.create(download=row, preset=Preset.P1080, video_format="137", audio_format="140")

    assert (media.preset, media.video_format, media.audio_format) == (Preset.P1080, "137", "140")
    assert "extractor" not in Media._meta.fields_map
    assert "video_id" not in Media._meta.fields_map


@pytest.mark.asyncio
async def test_media_names_the_title_and_kind_a_download_no_longer_stores(sqlite: None) -> None:
    row = await a_download()

    media = await Media.create(download=row, preset=Preset.BEST)

    # A bare video until the extract says otherwise; it has no place in a playlist.
    assert (media.title, media.kind, media.playlist_index) == ("", MediaKind.VIDEO, None)


@pytest.mark.asyncio
async def test_a_collection_orders_its_videos_by_their_place_in_the_playlist(sqlite: None) -> None:
    playlist = await a_download()
    await Media.create(download=playlist, preset=Preset.P720, kind=MediaKind.PLAYLIST, title="Talks")
    # Added in one bulk insert, so created_at cannot tell them apart; playlist_index can.
    for index in (3, 1, 2):
        await Media.create(download=await a_download(parent=playlist), preset=Preset.P720, playlist_index=index)

    members = await Media.filter(download__parent=playlist).order_by("playlist_index")

    assert [member.playlist_index for member in members] == [1, 2, 3]


@pytest.mark.asyncio
async def test_a_download_has_at_most_one_media(sqlite: None) -> None:
    row = await a_download()
    await Media.create(download=row, preset=Preset.BEST)

    with pytest.raises(IntegrityError):
        await Media.create(download=row, preset=Preset.BEST)


def test_a_torrent_has_no_detail_table() -> None:
    assert not hasattr(model, "TorrentDetail")
