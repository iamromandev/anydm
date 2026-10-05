import pytest
from src.data.db.model import Download, Media, PlaybackPosition
from src.data.repo import CollectionDatabaseRepo, FileDatabaseRepo
from src.data.repo.download.interface.collection import EntryRow
from src.data.type import DownloadStatus, MediaKind, Platform, Preset

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


def entry(video_id: str, position: int) -> EntryRow:
    return EntryRow(
        download={
            "source_url": f"https://youtu.be/{video_id}",
            "provider": "Youtube",
            "ref_id": video_id,
            "platform": Platform.SITE,
            "media_kind": MediaKind.VIDEO,
            "title": video_id,
            "status": DownloadStatus.PENDING,
            "position": position,
        },
        site={"preset": Preset.BEST},
        filename=f"{position:03d}_",
    )


COLLECTION = {
    "source_url": "https://youtube.com/playlist?list=PL",
    "provider": "Youtube",
    "ref_id": "PL",
    "platform": Platform.SITE,
    "media_kind": MediaKind.PLAYLIST,
    "title": "Talks",
    "path": "Talks [PL]",
    "status": DownloadStatus.PENDING,
    "preset": Preset.BEST,
}


@pytest.mark.asyncio
async def test_create_find_page_and_hold() -> None:
    repo = CollectionDatabaseRepo()
    collection = await repo.create_with_entries(COLLECTION, [entry("a", 1), entry("b", 2)])
    found = await repo.find("Youtube", "PL")
    assert found is not None and found.id == collection.id
    await repo.add_entries(collection, [entry("c", 3)])
    page, meta = await repo.downloads_page(collection.id, 1, 2)
    assert [row.ref_id for row in page] == ["a", "b"]
    assert meta.total == 3
    held = await repo.held(collection.id)
    assert {video_id: position for video_id, (_, _, position) in held.items()} == {"a": 1, "b": 2, "c": 3}
    # Members order by their place in the listing.
    ordered = (
        await Media.filter(download__parent_id=collection.id)
        .order_by("playlist_index")
        .values_list("playlist_index", flat=True)
    )
    assert ordered == [1, 2, 3]


@pytest.mark.asyncio
async def test_pause_resume_remove_and_the_all_variants() -> None:
    repo = CollectionDatabaseRepo()
    collection = await repo.create_with_entries(COLLECTION, [entry("a", 1), entry("b", 2)])
    first = (await repo.held(collection.id))["a"][0]
    await Download.filter(id=first).update(status=DownloadStatus.DOWNLOADING)

    assert await repo.pause(collection.id) == [first]
    assert {status for _, status, _, _ in await repo.member_rows(collection.id)} == {DownloadStatus.PAUSED}
    assert await repo.resume(collection.id) == 2

    _, touched = await repo.pause_all()
    assert touched == {collection.id}
    assert await repo.resume_all() == {collection.id}

    removed = await repo.remove(collection.id)
    assert len(removed) == 2
    assert await repo.member_rows(collection.id) == []


@pytest.mark.asyncio
async def test_watched_counts_through_files() -> None:
    repo, files = CollectionDatabaseRepo(), FileDatabaseRepo()
    collection = await repo.create_with_entries(COLLECTION, [entry("a", 1), entry("b", 2)])
    one = await files.single((await repo.held(collection.id))["a"][0])
    assert one is not None
    await PlaybackPosition.create(file_id=one.id, watched=True)
    assert await repo.watched_counts([collection.id]) == {collection.id: 1}
