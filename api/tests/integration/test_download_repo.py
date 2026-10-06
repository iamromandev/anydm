import uuid
from datetime import timedelta
from typing import Any

import pytest
from src.core.common import now
from src.data.db.model import Download, File, Media, Mirror, Provider, Source, Torrent, TorrentFile, Url
from src.data.repo import CollectionDatabaseRepo, DownloadDatabaseRepo
from src.data.type import DownloadStatus, MediaKind, Preset, SourceKind

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


async def add_site(
    repo: DownloadDatabaseRepo, video_id: str | None = None, *, url: str | None = None, **download: Any
) -> Download:
    return await repo.create_site(
        url=url or f"https://www.youtube.com/watch?v={video_id or uuid.uuid4().hex[:11]}",
        provider="Youtube",
        download={"status": DownloadStatus.PENDING, **download},
        media={"title": "Talk", "kind": MediaKind.VIDEO, "preset": Preset.BEST},
        filename="Talk_1080p.mp4",
        mime_type="video/mp4",
    )


async def a_collection() -> Download:
    return await CollectionDatabaseRepo().create_with_entries(
        url=f"https://www.youtube.com/playlist?list=PL{uuid.uuid4().hex[:8]}",
        provider="Youtube",
        collection={"status": DownloadStatus.PENDING},
        media={"kind": MediaKind.PLAYLIST, "title": "Talks", "preset": Preset.BEST},
        entries=[],
    )


@pytest.mark.asyncio
async def test_a_site_download_is_its_page_s_catalog_rows_and_its_own() -> None:
    row = await add_site(DownloadDatabaseRepo(), "dQw4w9WgXcQ", total_size=900)

    mirror = await Mirror.get(download_id=row.id).prefetch_related("source__url", "source__provider")
    assert (mirror.source.kind, mirror.source.provider.name, mirror.source.provider.slug) == (
        SourceKind.CONTENT,
        "Youtube",
        "youtube",
    )
    assert mirror.source.url.value == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    media = await Media.get(download_id=row.id)
    assert (media.title, media.kind, media.preset) == ("Talk", MediaKind.VIDEO, Preset.BEST)
    file = await File.get(download_id=row.id)
    assert (file.index, file.filename, file.mime_type) == (0, "Talk_1080p.mp4", "video/mp4")
    assert row.total_size == 900 and row.media is not None


@pytest.mark.asyncio
async def test_one_address_is_one_url_and_one_source_however_often_it_is_added() -> None:
    repo = DownloadDatabaseRepo()
    await add_site(repo, url="https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    await add_site(repo, url="https://www.youtube.com/watch?v=dQw4w9WgXcQ")

    assert (await Url.all().count(), await Source.all().count(), await Provider.all().count()) == (1, 1, 1)
    assert await Mirror.all().count() == 2


@pytest.mark.asyncio
async def test_a_direct_download_is_its_address_under_http() -> None:
    row = await DownloadDatabaseRepo().create_direct(
        url="https://e.com/a.iso", download={"status": DownloadStatus.PENDING}, filename="a.iso"
    )

    mirror = await Mirror.get(download_id=row.id).prefetch_related("source__url", "source__provider")
    assert (mirror.source.kind, mirror.source.provider.slug, mirror.source.url.value) == (
        SourceKind.DIRECT,
        "http",
        "https://e.com/a.iso",
    )
    assert (await File.get(download_id=row.id)).mime_type is not None
    assert row.media is None


@pytest.mark.asyncio
async def test_a_torrent_is_recorded_once_by_its_info_hash() -> None:
    repo = DownloadDatabaseRepo()
    first = await repo.create_torrent(
        url="magnet:?xt=urn:btih:" + "a" * 40,
        info_hash="a" * 40,
        name="T",
        total_size=10,
        download={"status": DownloadStatus.PENDING},
        files=[(0, "T/x.mkv", 10, True), (1, "T/x.nfo", 1, False)],
    )

    assert await repo.by_info_hash("a" * 40) is not None
    torrent = await Torrent.get(info_hash="a" * 40)
    assert (torrent.name, torrent.total_bytes) == ("T", 10)
    assert sorted(await TorrentFile.filter(torrent=torrent).values_list("path", flat=True)) == ["T/x.mkv", "T/x.nfo"]
    assert [(f.index, f.filename, f.selected) for f in await File.filter(download_id=first.id).order_by("index")] == [
        (0, "x.mkv", True),
        (1, "x.nfo", False),
    ]
    assert first.total_size == 10

    # Removed, then added again from another magnet: the same torrent, one row.
    await Download.filter(id=first.id).update(deleted_at=now())
    assert await repo.by_info_hash("a" * 40) is None
    again = await repo.create_torrent(
        url="magnet:?xt=urn:btih:" + "a" * 40 + "&tr=udp://tracker.example:80",
        info_hash="a" * 40,
        name="T",
        total_size=10,
        download={"status": DownloadStatus.PENDING},
        files=[(0, "T/x.mkv", 10, True)],
    )
    assert await Torrent.all().count() == 1
    found = await repo.by_info_hash("a" * 40)
    assert found is not None and found.id == again.id


@pytest.mark.asyncio
async def test_statuses_by_url_match_any_spelling_of_the_address_and_report_the_furthest_along() -> None:
    repo = DownloadDatabaseRepo()
    await add_site(repo, url="https://www.youtube.com/watch?v=aaaaaaaaaaa", status=DownloadStatus.FAILED)
    await add_site(repo, url="https://www.youtube.com/watch?v=aaaaaaaaaaa", status=DownloadStatus.COMPLETED)
    gone = await add_site(repo, url="https://www.youtube.com/watch?v=bbbbbbbbbbb")
    await Download.filter(id=gone.id).update(deleted_at=now())

    asked = ["HTTPS://WWW.YOUTUBE.COM/watch?v=aaaaaaaaaaa", "https://www.youtube.com/watch?v=bbbbbbbbbbb"]
    assert await repo.statuses_by_url(asked) == {asked[0]: DownloadStatus.COMPLETED}
    assert await repo.statuses_by_url([]) == {}


@pytest.mark.asyncio
async def test_a_container_holds_no_video() -> None:
    collection = await a_collection()
    url = (await Mirror.get(download_id=collection.id).prefetch_related("source__url")).source.url.value

    assert await DownloadDatabaseRepo().statuses_by_url([url]) == {}


@pytest.mark.asyncio
async def test_claim_takes_standalone_first_then_creation_order_and_honours_retry() -> None:
    repo = DownloadDatabaseRepo()
    collection = await a_collection()
    member = await add_site(repo, parent_id=collection.id)
    retrying = await add_site(repo, next_attempt_at=now() + timedelta(hours=1))
    ready = await add_site(repo)

    first = await repo.claim_next()
    assert first is not None and first.id == ready.id and first.status == DownloadStatus.DOWNLOADING
    second = await repo.claim_next()
    assert second is not None and second.id == member.id
    assert await repo.claim_next() is None
    assert retrying.id not in {first.id, second.id}


@pytest.mark.asyncio
async def test_recover_orphans_requeues_the_mid_flight() -> None:
    repo = DownloadDatabaseRepo()
    row = await add_site(repo)
    await Download.filter(id=row.id).update(status=DownloadStatus.MUXING)
    assert await repo.recover_orphans() == 1
    recovered = await repo.get_active_by_id(row.id)
    assert recovered is not None and recovered.status == DownloadStatus.PENDING


@pytest.mark.asyncio
async def test_a_try_s_outcome_is_written_only_over_a_row_still_in_the_worker_s_hands() -> None:
    repo = DownloadDatabaseRepo()
    running = await add_site(repo, status=DownloadStatus.DOWNLOADING)
    paused = await add_site(repo, status=DownloadStatus.PAUSED)
    removed = await add_site(repo, status=DownloadStatus.DOWNLOADING)
    await Download.filter(id=removed.id).update(deleted_at=now())
    failed = {"status": DownloadStatus.PENDING, "error": "refused", "attempts": 1}

    assert await repo.end_try(running.id, failed) is True
    assert await repo.end_try(paused.id, failed) is False
    assert await repo.end_try(removed.id, failed) is False

    rows = {row.id: row for row in await Download.filter(id__in=[running.id, paused.id, removed.id])}
    assert (rows[running.id].status, rows[running.id].error) == (DownloadStatus.PENDING, "refused")
    assert (rows[paused.id].status, rows[paused.id].error) == (DownloadStatus.PAUSED, None)
    assert rows[removed.id].status == DownloadStatus.DOWNLOADING
    # Completion may also land over a pause: the file is whole.
    done = {"status": DownloadStatus.COMPLETED}
    assert await repo.end_try(paused.id, done, over={DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED}) is True


@pytest.mark.asyncio
async def test_claim_skips_a_download_a_worker_still_holds() -> None:
    repo = DownloadDatabaseRepo()
    held = await add_site(repo)
    free = await add_site(repo)

    claimed = await repo.claim_next(exclude={held.id})
    assert claimed is not None and claimed.id == free.id
    assert await repo.claim_next(exclude={held.id}) is None
    # Let go of: claimable again.
    again = await repo.claim_next()
    assert again is not None and again.id == held.id


@pytest.mark.asyncio
async def test_held_at_finds_a_standalone_download_by_any_spelling_of_its_address() -> None:
    repo = DownloadDatabaseRepo()
    held = await add_site(repo, url="https://www.youtube.com/watch?v=aaaaaaaaaaa")
    gone = await add_site(repo, url="https://www.youtube.com/watch?v=bbbbbbbbbbb")
    await Download.filter(id=gone.id).update(deleted_at=now())
    collection = await a_collection()
    await add_site(repo, url="https://www.youtube.com/watch?v=ccccccccccc", parent_id=collection.id)

    found = await repo.held_at("HTTPS://WWW.YOUTUBE.COM/watch?v=aaaaaaaaaaa")
    assert found is not None and found.id == held.id and found.media is not None
    # Removed, a collection's video (#504), the collection itself, or never added: none holds it.
    assert await repo.held_at("https://www.youtube.com/watch?v=bbbbbbbbbbb") is None
    assert await repo.held_at("https://www.youtube.com/watch?v=ccccccccccc") is None
    playlist = (await Mirror.get(download_id=collection.id).prefetch_related("source__url")).source.url.value
    assert await repo.held_at(playlist) is None
    assert await repo.held_at("https://www.youtube.com/watch?v=ddddddddddd") is None
