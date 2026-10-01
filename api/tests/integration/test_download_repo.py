import uuid
from datetime import timedelta

import pytest
from src.core.common import now
from src.data.db.model import Collection, Download
from src.data.repo import DownloadDatabaseRepo
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


def site_fields(title: str = "Talk") -> dict:
    return {
        "source_url": f"https://youtu.be/{uuid.uuid4().hex[:8]}",
        "platform": Platform.SITE,
        "media_kind": MediaKind.VIDEO,
        "title": title,
        "status": DownloadStatus.PENDING,
    }


def site_detail(video_id: str = "abc") -> dict:
    return {"extractor": "Youtube", "video_id": video_id, "preset": Preset.BEST}


async def a_collection() -> Collection:
    return await Collection.create(
        kind=CollectionKind.PLAYLIST,
        source_url="https://youtube.com/playlist?list=PL",
        extractor="YoutubeTab",
        external_id=uuid.uuid4().hex,
        folder="Talks",
        preset=Preset.BEST,
    )


@pytest.mark.asyncio
async def test_creates_put_each_platform_in_main_with_its_rows() -> None:
    repo = DownloadDatabaseRepo()
    site = await repo.create_site(site_fields(), site_detail(), "Talk_1080p.mp4", "video/mp4")
    direct = await repo.create_direct(
        {
            "source_url": "https://e.com/a.iso",
            "platform": Platform.DIRECT,
            "media_kind": MediaKind.FILE,
            "title": "a.iso",
            "status": DownloadStatus.PENDING,
        },
        "a.iso",
    )
    torrent = await repo.create_torrent(
        {
            "source_url": "magnet:?xt=urn:btih:" + "a" * 40,
            "platform": Platform.TORRENT,
            "media_kind": MediaKind.FILE,
            "title": "T",
            "status": DownloadStatus.PENDING,
            "folder": "torrent/T",
        },
        "a" * 40,
        [(0, "x.mkv", 10, True)],
    )
    assert site.queue.name == "Main"
    assert site.site_detail.video_id == "abc"
    assert torrent.torrent_detail.info_hash == "a" * 40
    assert [site.queue_position, direct.queue_position, torrent.queue_position] == [0, 1, 2]
    found = await repo.by_info_hash("a" * 40)
    assert found is not None and found.id == torrent.id


@pytest.mark.asyncio
async def test_claim_takes_standalone_first_then_queue_order_and_honours_start_and_retry() -> None:
    repo = DownloadDatabaseRepo()
    collection = await a_collection()
    member = await repo.create_site(
        {**site_fields(), "collection_id": collection.id, "position": 1}, site_detail("m"), "001_", None
    )
    later = await repo.create_site(
        {**site_fields(), "start_at": now() + timedelta(hours=1)}, site_detail("s"), "s", None
    )
    retrying = await repo.create_site(
        {**site_fields(), "next_attempt_at": now() + timedelta(hours=1)}, site_detail("r"), "r", None
    )
    ready = await repo.create_site(site_fields(), site_detail("x"), "x", None)

    first = await repo.claim_next()
    assert first is not None and first.id == ready.id and first.status == DownloadStatus.DOWNLOADING
    second = await repo.claim_next()
    assert second is not None and second.id == member.id
    assert await repo.claim_next() is None
    assert {later.id, retrying.id}.isdisjoint({first.id, second.id})


@pytest.mark.asyncio
async def test_recover_orphans_requeues_the_mid_flight() -> None:
    repo = DownloadDatabaseRepo()
    row = await repo.create_site(site_fields(), site_detail(), "x", None)
    await Download.filter(id=row.id).update(status=DownloadStatus.MUXING)
    assert await repo.recover_orphans() == 1
    recovered = await repo.get_active_by_id(row.id)
    assert recovered is not None and recovered.status == DownloadStatus.PENDING


@pytest.mark.asyncio
async def test_list_items_tags_pages_filters_and_sorts_by_live_speed() -> None:
    repo = DownloadDatabaseRepo()
    slow = await repo.create_site(site_fields("slow"), site_detail("1"), "1", None)
    fast = await repo.create_site(site_fields("fast"), site_detail("2"), "2", None)
    collection = await a_collection()
    await repo.create_site(
        {**site_fields(), "collection_id": collection.id, "status": DownloadStatus.PAUSED},
        site_detail("3"),
        "3",
        None,
    )

    items, meta = await repo.list_items(1, 10, None, "-speed_bps", {fast.id: 900, slow.id: 5})
    assert items[:2] == [("download", fast.id), ("download", slow.id)]
    assert ("collection", collection.id) in items
    assert meta.total == 3

    paused, _ = await repo.list_items(1, 10, [DownloadStatus.PAUSED], "-created_at", {})
    assert paused == [("collection", collection.id)]

    summary = await repo.summary()
    assert (summary.all, summary.downloading) == (3, 2)


@pytest.mark.asyncio
async def test_statuses_by_video_reports_the_furthest_along() -> None:
    repo = DownloadDatabaseRepo()
    await repo.create_site({**site_fields(), "status": DownloadStatus.FAILED}, site_detail("v"), "a", None)
    await repo.create_site({**site_fields(), "status": DownloadStatus.COMPLETE}, site_detail("v"), "b", None)
    assert await repo.statuses_by_video("Youtube", ["v", "none"]) == {"v": DownloadStatus.COMPLETE}
