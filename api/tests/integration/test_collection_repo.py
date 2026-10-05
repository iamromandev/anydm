import pytest
from src.data.db.model import Download, File, Media, Mirror, PlaybackPosition, Source, Url
from src.data.repo import CollectionDatabaseRepo, DownloadDatabaseRepo, FileDatabaseRepo
from src.data.repo.download.interface.collection import EntryRow
from src.data.type import DownloadStatus, MediaKind, Preset

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

PLAYLIST = "https://www.youtube.com/playlist?list=PLtalks"


def video(letter: str) -> str:
    """An 11-character id, the shape a YouTube address carries."""
    return letter * 11


def entry(letter: str, index: int) -> EntryRow:
    return EntryRow(
        url=f"https://www.youtube.com/watch?v={video(letter)}",
        download={"status": DownloadStatus.PENDING},
        media={"title": letter, "kind": MediaKind.VIDEO, "preset": Preset.BEST, "playlist_index": index},
        filename=f"{index:03d}_",
    )


async def a_collection(*entries: EntryRow) -> Download:
    return await CollectionDatabaseRepo().create_with_entries(
        url=PLAYLIST,
        provider="Youtube",
        collection={"status": DownloadStatus.PENDING},
        media={"kind": MediaKind.PLAYLIST, "title": "Talks", "preset": Preset.BEST},
        entries=list(entries),
    )


@pytest.mark.asyncio
async def test_create_find_page_and_hold() -> None:
    repo = CollectionDatabaseRepo()
    collection = await a_collection(entry("a", 1), entry("b", 2))
    found = await repo.find("https://WWW.youtube.com/playlist?list=PLtalks")
    assert found is not None and found.id == collection.id
    assert await repo.find("https://www.youtube.com/playlist?list=PLother") is None
    await repo.add_entries(collection, "Youtube", [entry("c", 3)])

    page, meta = await repo.downloads_page(collection.id, 1, 2)
    assert [row.media.title if row.media else None for row in page] == ["a", "b"]
    assert meta.total == 3
    held = await repo.held(collection.id)
    assert set(held) == {video("a"), video("b"), video("c")}
    # Members order by their place in the listing.
    ordered = (
        await Media.filter(download__parent_id=collection.id)
        .order_by("playlist_index")
        .values_list("playlist_index", flat=True)
    )
    assert ordered == [1, 2, 3]
    assert await File.filter(download__parent_id=collection.id, index=0).count() == 3


@pytest.mark.asyncio
async def test_a_video_held_elsewhere_shares_its_address_and_is_marked() -> None:
    single = await DownloadDatabaseRepo().create_site(
        url=f"https://www.youtube.com/watch?v={video('a')}",
        provider="Youtube",
        download={"status": DownloadStatus.COMPLETED},
        media={"title": "a", "kind": MediaKind.VIDEO, "preset": Preset.BEST},
        filename="a.mp4",
        mime_type=None,
    )
    collection = await a_collection(entry("a", 1), entry("b", 2))

    # One address, one source: the single download and the collection's video share them.
    assert await Url.filter(value__contains="watch").count() == 2
    assert await Source.all().count() == 3
    members = await Mirror.filter(download__parent_id=collection.id).values_list("source_id", flat=True)
    single_source = await Mirror.get(download_id=single.id).values_list("source_id", flat=True)
    assert single_source in members
    marked = await DownloadDatabaseRepo().statuses_by_url([f"https://www.youtube.com/watch?v={video('a')}"])
    assert list(marked.values()) == [DownloadStatus.COMPLETED]


@pytest.mark.asyncio
async def test_pause_resume_remove_and_the_all_variants() -> None:
    repo = CollectionDatabaseRepo()
    collection = await a_collection(entry("a", 1), entry("b", 2))
    first = (await repo.held(collection.id))[video("a")][0]
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
    collection = await a_collection(entry("a", 1), entry("b", 2))
    one = await files.single((await repo.held(collection.id))[video("a")][0])
    assert one is not None
    await PlaybackPosition.create(file_id=one.id, watched=True)
    assert await repo.watched_counts([collection.id]) == {collection.id: 1}
