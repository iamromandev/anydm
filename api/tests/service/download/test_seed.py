import pytest
from src.data.repo.download.interface.folder import FolderRow
from src.data.repo.download.interface.queue import QueueRow
from src.service.download.seed import FOLDERS, seed_organization


class FakeFolders:
    def __init__(self, have: set[str]) -> None:
        self.have = have
        self.inserted: list[FolderRow] = []

    async def insert_missing(self, rows: list[FolderRow]) -> int:
        fresh = [row for row in rows if row.name not in self.have]
        self.inserted += fresh
        return len(fresh)


class FakeQueues:
    def __init__(self) -> None:
        self.inserted: list[QueueRow] = []

    async def insert_missing(self, rows: list[QueueRow]) -> int:
        self.inserted += rows
        return len(rows)


def test_six_folders_and_no_extension_twice() -> None:
    assert [f.name for f in FOLDERS] == ["Video", "Music", "Software", "Documents", "Compressed", "Other"]
    every = [ext for f in FOLDERS for ext in f.extensions]
    assert len(every) == len(set(every))
    assert FOLDERS[-1].extensions == ()


def test_each_slug_is_its_name_lowercased_and_unique() -> None:
    assert [f.slug for f in FOLDERS] == [f.name.lower() for f in FOLDERS]
    assert len({f.slug for f in FOLDERS}) == len(FOLDERS)


@pytest.mark.asyncio
async def test_seed_adds_only_what_is_missing_and_main_takes_the_worker_count() -> None:
    folders, queues = FakeFolders(have={"Video"}), FakeQueues()
    assert await seed_organization(folders, queues, workers=3) == (5, 1)  # ty: ignore[invalid-argument-type]
    assert "Video" not in [row.name for row in folders.inserted]
    assert queues.inserted == [QueueRow("Main", 3, 0)]
