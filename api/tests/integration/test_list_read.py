"""The list, a download's schema and a collection's, read from the current models on Postgres."""

import pytest
from src.data.db.model import Category, Download
from src.data.repo import CollectionDatabaseRepo, DownloadDatabaseRepo, FileDatabaseRepo, PositionDatabaseRepo
from src.data.type import DownloadStatus, MediaKind, Platform, Preset
from src.lib.event import EventHub
from src.service.download.collection_totals import CollectionTotals
from src.service.download.live import LiveStats
from src.service.download.views import DownloadViews

from tests.integration.rows import a_collection, a_download, a_site_download, a_torrent_download

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


@pytest.mark.asyncio
async def test_list_items_tags_pages_filters_and_sorts_by_live_speed() -> None:
    repo = DownloadDatabaseRepo()
    slow = await a_site_download("slow", title="Slow")
    fast = await a_site_download("fast", title="Fast")
    collection = await a_collection()
    await a_site_download("member", parent=collection, status=DownloadStatus.PAUSED)

    items, meta = await repo.list_items(1, 10, None, "-speed_bps", {fast.id: 900, slow.id: 5})
    assert items[:2] == [("download", fast.id), ("download", slow.id)]
    assert ("collection", collection.id) in items
    assert meta.total == 3

    paused, _ = await repo.list_items(1, 10, [DownloadStatus.PAUSED], "-created_at", {})
    assert paused == [("collection", collection.id)]

    summary = await repo.summary()
    assert (summary.all, summary.downloading) == (3, 2)


@pytest.mark.asyncio
async def test_the_list_titles_each_kind_from_where_it_came_from() -> None:
    repo = DownloadDatabaseRepo()
    site = await a_site_download("v", title="b site")
    torrent = await a_torrent_download("c" * 40, name="c torrent")
    direct = await a_download(filename="a direct.iso")

    items, _ = await repo.list_items(1, 10, None, "title", {})

    assert [item_id for _, item_id in items] == [direct.id, site.id, torrent.id]


@pytest.mark.asyncio
async def test_a_collection_is_downloading_while_a_video_is_queued_and_completed_after() -> None:
    repo = DownloadDatabaseRepo()
    collection = await a_collection()
    queued = await a_site_download("q", parent=collection, status=DownloadStatus.QUEUED, total_size=10)
    await a_site_download("d", parent=collection, status=DownloadStatus.COMPLETED, total_size=30, downloaded_size=30)

    (only,), _ = await repo.list_items(1, 10, [DownloadStatus.DOWNLOADING], "created_at", {})
    assert only == ("collection", collection.id)

    queued.status = DownloadStatus.COMPLETED
    await queued.save(update_fields=["status"])
    (done,), _ = await repo.list_items(1, 10, [DownloadStatus.COMPLETED], "-total_size", {})
    assert done == ("collection", collection.id)


@pytest.mark.asyncio
async def test_a_download_schema_reads_its_source_media_and_torrent() -> None:
    repo = DownloadDatabaseRepo()
    views = DownloadViews(FileDatabaseRepo(), PositionDatabaseRepo(), LiveStats(), max_attempts=3)
    site = await a_site_download("dQw4w9WgXcQ", title="Never", total_size=200, downloaded_size=50, speed_limit=1000)
    torrent = await a_torrent_download("b" * 40, name="Bunny", uploaded_size=7)
    direct = await a_download("https://example.com/x/ubuntu.iso", filename="ubuntu.iso")

    by_id = {schema.id: schema for schema in await views.many(await repo.by_ids([site.id, torrent.id, direct.id]))}

    one = by_id[site.id]
    assert (one.platform, one.media_kind, one.title) == (Platform.SITE, MediaKind.VIDEO, "Never")
    assert one.url == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    assert one.site is not None and (one.site.extractor, one.site.video_id) == ("Youtube", "dQw4w9WgXcQ")
    assert (one.site.preset, one.progress, one.limits.download_bps) == (Preset.P1080, 25, 1000)
    two = by_id[torrent.id]
    assert two.torrent is not None and (two.torrent.info_hash, two.torrent.uploaded_bytes) == ("b" * 40, 7)
    assert (two.platform, two.title, two.site) == (Platform.TORRENT, "Bunny", None)
    three = by_id[direct.id]
    assert (three.platform, three.title, three.site, three.torrent) == (Platform.DIRECT, "ubuntu.iso", None, None)
    assert [file.path for file in three.files] == ["ubuntu.iso"]


@pytest.mark.asyncio
async def test_a_collection_reads_its_videos_in_listing_order_and_by_their_ids() -> None:
    repo = CollectionDatabaseRepo()
    collection = await a_collection("PLtalks", title="Talks")
    second = await a_site_download("two", parent=collection, playlist_index=2, status=DownloadStatus.QUEUED)
    first = await a_site_download("one", parent=collection, playlist_index=1, status=DownloadStatus.COMPLETED)

    (found,) = await repo.by_ids([collection.id])
    page, meta = await repo.downloads_page(collection.id, 1, 10)
    held = await repo.held(collection.id)
    schema = await CollectionTotals(repo, EventHub(), LiveStats()).schema(found)

    assert [row.id for row in page] == [first.id, second.id] and meta.total == 2
    assert held == {"one": (first.id, DownloadStatus.COMPLETED), "two": (second.id, DownloadStatus.QUEUED)}
    assert (schema.extractor, schema.external_id, schema.title) == ("Youtube", "PLtalks", "Talks")
    assert schema.url == "https://www.youtube.com/playlist?list=PLtalks"
    assert (schema.preset, schema.status, schema.progress) == (Preset.P720, DownloadStatus.DOWNLOADING, 50)
    assert await DownloadDatabaseRepo().get_active_by_id(collection.id) is None


@pytest.mark.asyncio
async def test_a_category_narrows_the_list_and_the_counts_to_its_items() -> None:
    repo = DownloadDatabaseRepo()
    lectures = await Category.create(name="Lectures", slug="lectures", folder="edu/lectures", position=99)
    in_lectures = await a_site_download("m", title="In lectures")
    await Download.filter(id=in_lectures.id).update(category_id=lectures.id)
    await a_site_download("d", title="In downloads")

    items, meta = await repo.list_items(1, 10, None, "-created_at", {}, lectures.id)
    assert items == [("download", in_lectures.id)] and meta.total == 1

    everything = await repo.summary()
    narrowed = await repo.summary(lectures.id)
    assert (everything.all, narrowed.all) == (2, 1)
