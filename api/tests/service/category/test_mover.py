import uuid
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from src.core.error import Error
from src.data.type import DOWNLOADS_ID, DownloadStatus, MediaKind, Platform, Preset
from src.service.category.mover import CategoryMover

from tests.service.download.described_rows import a_site_row
from tests.service.download.memory import MemoryFiles, RecordingHub, download_row, file_row, memory_views

from .fake_category_repo import FakeCategoryRepo


def _category(row: Any) -> SimpleNamespace:
    return SimpleNamespace(id=row.id, name=row.name, folder=row.folder)


class Downloads:
    def __init__(self, *rows: Any) -> None:
        self.rows = {row.id: row for row in rows}
        self.set: list[tuple[uuid.UUID, uuid.UUID]] = []
        #: Set by ``_mover``: what a row's category becomes, as a reload would show it.
        self.categories: FakeCategoryRepo | None = None

    async def get_active_by_id(self, download_id: uuid.UUID) -> Any:
        return self.rows.get(download_id)

    async def set_category(self, download_id: uuid.UUID, category_id: uuid.UUID) -> None:
        self.set.append((download_id, category_id))
        row = self.rows[download_id]
        row.category_id = category_id
        if self.categories is not None:
            row.category = _category(self.categories.rows[category_id])


class Collections:
    def __init__(self, collection: Any, members: list[Any] | None = None) -> None:
        self.collection = collection
        self.members = members or []
        self.moved: list[tuple[uuid.UUID, uuid.UUID]] = []
        self.categories: FakeCategoryRepo | None = None

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Any:
        return self.collection if self.collection.id == collection_id else None

    async def member_rows(self, collection_id: uuid.UUID) -> list[Any]:
        return [(m.id, m.status, m.downloaded_size, m.total_size) for m in self.members]

    async def move_rows(self, collection_id: uuid.UUID, category_id: uuid.UUID) -> None:
        self.moved.append((collection_id, category_id))
        self.collection.category_id = category_id
        if self.categories is not None:
            self.collection.category = _category(self.categories.rows[category_id])


class Totals:
    def __init__(self) -> None:
        self.refreshed: list[uuid.UUID] = []

    async def refresh(self, collection_id: uuid.UUID) -> Any:
        self.refreshed.append(collection_id)
        return SimpleNamespace(id=collection_id)


def _mover(
    tmp_path: Path,
    downloads: Downloads,
    *,
    files: MemoryFiles | None = None,
    collections: Collections | None = None,
    categories: FakeCategoryRepo | None = None,
) -> tuple[CategoryMover, FakeCategoryRepo, MemoryFiles]:
    categories = categories or FakeCategoryRepo()
    files = files or MemoryFiles()
    collections = collections or Collections(download_row(parent_id=None))
    downloads.categories, collections.categories = categories, categories
    mover = CategoryMover(
        downloads=downloads,
        collections=collections,
        categories=categories,
        files=files,
        views=memory_views(files=files),
        totals=Totals(),
        hub=RecordingHub(),  # ty: ignore[invalid-argument-type]
        downloads_root=tmp_path,
    )
    return mover, categories, files


def _playlist(category_folder: str = "", **fields: Any) -> Any:
    """A collection of the playlist ``PL1`` called Talks, which lives in ``Talks [PL1]`` under its category's folder."""
    fields.setdefault("category", SimpleNamespace(id=DOWNLOADS_ID, name="Downloads", folder=category_folder))
    fields.setdefault("category_id", DOWNLOADS_ID)
    return a_site_row(
        "https://www.youtube.com/playlist?list=PL1",
        provider="Youtube",
        title="Talks",
        kind=MediaKind.PLAYLIST,
        preset=Preset.BEST,
        id=uuid.uuid4(),
        status=DownloadStatus.COMPLETED,
        **fields,
    )


@pytest.mark.asyncio
async def test_a_pending_download_only_changes_category(tmp_path: Path) -> None:
    row = download_row(status=DownloadStatus.PENDING)
    downloads = Downloads(row)
    mover, categories, _ = _mover(tmp_path, downloads)
    music = categories.named("music")

    await mover.move_download(row.id, music.id)

    assert downloads.set == [(row.id, music.id)]


@pytest.mark.asyncio
async def test_a_finished_download_moves_with_its_subtitles(tmp_path: Path) -> None:
    row = download_row(status=DownloadStatus.COMPLETED)
    (tmp_path / "a.mp4").write_bytes(b"x")
    (tmp_path / "a.en.vtt").write_bytes(b"x")
    files = MemoryFiles()
    files.by_download[row.id] = [file_row(path="a.mp4")]
    downloads = Downloads(row)
    mover, categories, _ = _mover(tmp_path, downloads, files=files)
    music = categories.named("music")

    schema = await mover.move_download(row.id, music.id)

    assert (tmp_path / "music" / "a.mp4").is_file() and (tmp_path / "music" / "a.en.vtt").is_file()
    assert downloads.set == [(row.id, music.id)]
    # Where it is now follows from its new category.
    assert schema.folder == "music"


@pytest.mark.asyncio
async def test_repeating_a_move_after_a_crash_only_writes_the_row(tmp_path: Path) -> None:
    row = download_row(status=DownloadStatus.COMPLETED)
    (tmp_path / "music").mkdir()
    (tmp_path / "music" / "a.mp4").write_bytes(b"x")
    files = MemoryFiles()
    files.by_download[row.id] = [file_row(path="a.mp4")]
    downloads = Downloads(row)
    mover, categories, _ = _mover(tmp_path, downloads, files=files)
    music = categories.named("music")

    await mover.move_download(row.id, music.id)

    assert downloads.set == [(row.id, music.id)]


@pytest.mark.asyncio
async def test_a_name_taken_in_the_new_folder_is_renamed_and_the_row_follows(tmp_path: Path) -> None:
    row = download_row(status=DownloadStatus.COMPLETED)
    (tmp_path / "music").mkdir()
    (tmp_path / "music" / "a.mp4").write_bytes(b"taken")
    (tmp_path / "a.mp4").write_bytes(b"x")
    files = MemoryFiles()
    files.by_download[row.id] = [file_row(path="a.mp4")]
    mover, categories, _ = _mover(tmp_path, Downloads(row), files=files)

    await mover.move_download(row.id, categories.named("music").id)

    short = str(row.id)[:8]
    assert (tmp_path / "music" / f"a_{short}.mp4").is_file()
    renamed = await files.single(row.id)
    assert renamed is not None and renamed.path == f"a_{short}.mp4"


@pytest.mark.asyncio
async def test_two_categories_sharing_a_folder_move_no_file(tmp_path: Path) -> None:
    (tmp_path / "edu").mkdir()
    (tmp_path / "edu" / "a.mp4").write_bytes(b"x")
    files = MemoryFiles()
    categories = FakeCategoryRepo()
    lectures = await categories.create("Lectures", "lectures", "edu")
    row = download_row(
        status=DownloadStatus.COMPLETED,
        category_id=lectures.id,
        category=SimpleNamespace(id=lectures.id, name="Lectures", folder="edu"),
    )
    files.by_download[row.id] = [file_row(path="a.mp4")]
    downloads = Downloads(row)
    mover, _, _ = _mover(tmp_path, downloads, files=files, categories=categories)
    shared = await categories.create("Shared", "shared", "edu")

    await mover.move_download(row.id, shared.id)

    assert (tmp_path / "edu" / "a.mp4").is_file()
    assert downloads.set == [(row.id, shared.id)]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("row", "code", "message"),
    [
        (download_row(status=DownloadStatus.DOWNLOADING), 409, "Download is downloading; move it once it stops or finishes"),
        (download_row(status=DownloadStatus.MUXING), 409, "Download is muxing; move it once it stops or finishes"),
        (
            download_row(platform=Platform.TORRENT, url="magnet:?xt=urn:btih:ab"),
            422,
            "A torrent stays in the category it was added to",
        ),
        (download_row(parent_id=uuid.uuid4()), 422, "Move the collection this video belongs to"),
    ],
)
async def test_refusals(tmp_path: Path, row: Any, code: int, message: str) -> None:
    mover, categories, _ = _mover(tmp_path, Downloads(row))

    with pytest.raises(Error) as raised:
        await mover.move_download(row.id, categories.named("music").id)

    assert (int(raised.value.code), raised.value.message) == (code, message)


@pytest.mark.asyncio
async def test_an_unknown_category_is_422_and_the_same_one_is_a_no_op(tmp_path: Path) -> None:
    row = download_row(status=DownloadStatus.COMPLETED)
    downloads = Downloads(row)
    mover, _, _ = _mover(tmp_path, downloads)

    with pytest.raises(Error) as raised:
        await mover.move_download(row.id, uuid.uuid4())
    assert int(raised.value.code) == 422

    await mover.move_download(row.id, row.category_id)
    assert downloads.set == []


@pytest.mark.asyncio
async def test_a_collection_moves_its_folder_whole_and_its_videos_with_it(tmp_path: Path) -> None:
    collection = _playlist()
    (tmp_path / "Talks [PL1]").mkdir()
    (tmp_path / "Talks [PL1]" / "01.mp4").write_bytes(b"x")
    collections = Collections(collection)
    mover, categories, _ = _mover(tmp_path, Downloads(), collections=collections)
    music = categories.named("music")

    await mover.move_collection(collection.id, music.id)

    assert (tmp_path / "music" / "Talks [PL1]" / "01.mp4").is_file()
    assert not (tmp_path / "Talks [PL1]").exists()
    assert collections.moved == [(collection.id, music.id)]


@pytest.mark.asyncio
async def test_a_collection_is_not_moved_over_a_folder_of_the_same_name(tmp_path: Path) -> None:
    collection = _playlist()
    (tmp_path / "Talks [PL1]").mkdir()
    (tmp_path / "Talks [PL1]" / "01.mp4").write_bytes(b"x")
    (tmp_path / "music" / "Talks [PL1]").mkdir(parents=True)
    collections = Collections(collection)
    mover, categories, _ = _mover(tmp_path, Downloads(), collections=collections)

    with pytest.raises(Error) as raised:
        await mover.move_collection(collection.id, categories.named("music").id)

    # Where its files are follows from its category, so it can't be set aside under another name.
    assert int(raised.value.code) == 409
    assert raised.value.message == "Music already holds a folder named Talks [PL1]; move or rename it first"
    assert (tmp_path / "Talks [PL1]" / "01.mp4").is_file() and collections.moved == []


@pytest.mark.asyncio
async def test_categories_sharing_a_folder_leave_a_collection_where_it_is(tmp_path: Path) -> None:
    categories = FakeCategoryRepo()
    first = await categories.create("First", "first", "edu")
    second = await categories.create("Second", "second", "edu")
    collection = _playlist("edu", category_id=first.id)
    (tmp_path / "edu" / "Talks [PL1]").mkdir(parents=True)
    (tmp_path / "edu" / "Talks [PL1]" / "01.mp4").write_bytes(b"x")
    collections = Collections(collection)
    mover, _, _ = _mover(tmp_path, Downloads(), collections=collections, categories=categories)

    await mover.move_collection(collection.id, second.id)

    assert (tmp_path / "edu" / "Talks [PL1]" / "01.mp4").is_file()
    assert collections.moved == [(collection.id, second.id)]


@pytest.mark.asyncio
async def test_a_collection_with_a_running_video_is_refused(tmp_path: Path) -> None:
    collection = _playlist()
    running = download_row(status=DownloadStatus.DOWNLOADING, parent_id=collection.id)
    mover, categories, _ = _mover(tmp_path, Downloads(), collections=Collections(collection, [running]))

    with pytest.raises(Error) as raised:
        await mover.move_collection(collection.id, categories.named("music").id)

    assert int(raised.value.code) == 409
    assert raised.value.message == "A video in this collection is downloading; move it once that finishes"
