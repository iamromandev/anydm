"""The models build a schema and relate the way the services read them.

In-memory SQLite: no Postgres needed, so this runs with the unit suite.
"""

import pytest
from src.data.db.model import (
    Collection,
    Download,
    DownloadFile,
    Folder,
    Mirror,
    PlaybackPosition,
    Queue,
    Segment,
    SiteDetail,
    TorrentDetail,
)
from src.data.type import (
    CollectionKind,
    DownloadStatus,
    MediaKind,
    Platform,
    Preset,
    SegmentPart,
)


@pytest.mark.asyncio
async def test_a_site_download_with_its_detail_file_and_position(sqlite: None) -> None:
    main = await Queue.create(name="Main", max_concurrent=2)
    video = await Folder.create(name="Video", slug="video", save_dir="Video", extensions=["mp4"])
    row = await Download.create(
        source_url="https://youtu.be/x",
        platform=Platform.SITE,
        media_kind=MediaKind.VIDEO,
        status=DownloadStatus.PENDING,
        queue=main,
        folder=video,
    )
    await SiteDetail.create(download=row, extractor="Youtube", video_id="x", preset=Preset.BEST)
    file = await DownloadFile.create(download=row, index=0, path="x.mp4")
    await PlaybackPosition.create(file=file, position_seconds=12.5, duration_seconds=60)
    await Segment.create(download=row, part=SegmentPart.VIDEO, index=0, start_byte=0, end_byte=9)

    loaded = await Download.get(id=row.id).prefetch_related("site_detail", "folder", "queue")
    assert loaded.site_detail is not None and loaded.site_detail.video_id == "x"
    assert loaded.folder is not None and loaded.folder.extensions == ["mp4"]
    assert loaded.queue.name == "Main"
    played = await DownloadFile.get(id=file.id).prefetch_related("playback")
    assert played.playback is not None and played.playback.position_seconds == 12.5


@pytest.mark.asyncio
async def test_a_torrent_and_a_collection(sqlite: None) -> None:
    main = await Queue.create(name="Main")
    collection = await Collection.create(
        kind=CollectionKind.PLAYLIST,
        extractor="YoutubeTab",
        ref_id="PL",
        preset=Preset.BEST,
    )
    torrent = await Download.create(
        source_url="magnet:?xt=urn:btih:aa",
        platform=Platform.TORRENT,
        media_kind=MediaKind.FILE,
        status=DownloadStatus.SEEDING,
        queue=main,
    )
    await TorrentDetail.create(download=torrent, info_hash="aa" * 20)
    await Mirror.create(download=torrent, url="https://mirror", position=0)
    member = await Download.create(
        source_url="https://youtu.be/y",
        platform=Platform.SITE,
        media_kind=MediaKind.VIDEO,
        status=DownloadStatus.PENDING,
        queue=main,
        collection=collection,
        position=1,
    )
    assert member.collection_id == collection.id
    loaded = await Download.get(id=torrent.id).prefetch_related("torrent_detail")
    assert loaded.torrent_detail is not None and loaded.torrent_detail.info_hash == "aa" * 20
    assert await Download.filter(collection=collection).count() == 1


def test_a_collection_keeps_no_url_of_its_own() -> None:
    """Its videos each carry their own address; the group is told apart by extractor and id."""
    assert "source_url" not in Collection._meta.fields_map
