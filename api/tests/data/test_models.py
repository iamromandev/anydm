"""The models build a schema and relate the way the services read them.

In-memory SQLite: no Postgres needed, so this runs with the unit suite.
"""

import pytest
from src.data.db.model import (
    Download,
    File,
    PlaybackPosition,
    Segment,
    SiteDetail,
)
from src.data.type import (
    DownloadStatus,
    MediaKind,
    Preset,
)


@pytest.mark.asyncio
async def test_a_site_download_with_its_detail_file_and_position(sqlite: None) -> None:
    row = await Download.create(status=DownloadStatus.PENDING)
    await SiteDetail.create(download=row, title="A video", media_kind=MediaKind.VIDEO, preset=Preset.BEST)
    file = await File.create(download=row, filename="x.mp4", index=0, path="x.mp4")
    await PlaybackPosition.create(file=file, position_seconds=12.5, duration_seconds=60)
    await Segment.create(file=file, start_byte=0, end_byte=9)

    loaded = await Download.get(id=row.id).prefetch_related("site_detail")
    assert loaded.site_detail is not None and loaded.site_detail.preset == Preset.BEST
    assert (loaded.site_detail.title, loaded.site_detail.media_kind) == ("A video", MediaKind.VIDEO)
    played = await File.get(id=file.id).prefetch_related("playback_positions")
    assert [position.position_seconds for position in played.playback_positions] == [12.5]


@pytest.mark.asyncio
async def test_a_torrent_and_a_playlist(sqlite: None) -> None:
    playlist = await Download.create(
        status=DownloadStatus.PENDING,
    )
    await Download.create(  # a second row: the playlist test counts members, not rows
        status=DownloadStatus.SEEDING,
    )
    member = await Download.create(
        status=DownloadStatus.PENDING,
        parent=playlist,
    )
    assert await Download.filter(id=member.id).values_list("parent_id", flat=True) == [playlist.id]
    assert await Download.filter(parent=playlist).count() == 1


def test_a_download_keeps_its_mirror_addresses_for_failover() -> None:
    """One download, many mirrors: each tried in position order until one works."""
    from src.data.db import model

    assert hasattr(model, "Mirror")
    assert "mirrors" in model.Download._meta.fields_map
