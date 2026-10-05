"""Playlists and channel tabs, added and run as collections (v0.5)."""

import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.success import Meta
from src.core.type import Code
from src.data.schema.transfer import CollectionEntryRequest, CollectionRequest
from src.data.type import DownloadStatus, MediaKind, Preset
from src.service.download.collection_service import CollectionService
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.live import LiveStats

from tests.service.download.described_rows import a_site_row
from tests.service.download.memory import RecordingHub, download_row, memory_views


class FakeCollections:
    def __init__(self) -> None:
        self.collection: Any = None
        self.created: dict[str, Any] | None = None
        self.entries: list[Any] = []
        self.added: list[Any] = []
        #: What ``held`` answers: video id -> (download id, status).
        self.held_rows: dict[str, tuple[uuid.UUID, DownloadStatus]] = {}
        self.requeued: list[uuid.UUID] = []
        self.calls: list[tuple[str, uuid.UUID]] = []
        #: What ``pause`` and ``remove`` answer: the downloads that were running.
        self.running: list[uuid.UUID] = []
        self.page: list[Any] = []
        self.deleted = False

    async def find(self, url: str) -> Any:
        return self.collection

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Any:
        if self.collection is None or self.deleted or self.collection.id != collection_id:
            return None
        return self.collection

    async def create_with_entries(self, **created: Any) -> Any:
        self.created, self.entries = created, list(created["entries"])
        media = created["media"]
        self.collection = a_site_row(
            created["url"],
            provider=created["provider"],
            title=media["title"],
            kind=media["kind"],
            preset=media["preset"],
            id=uuid.uuid4(),
            created_at=None,
        )
        return self.collection

    async def add_entries(self, collection: Any, provider: str, entries: list[Any]) -> None:
        self.added += entries

    async def held(self, collection_id: uuid.UUID) -> dict[str, tuple[uuid.UUID, DownloadStatus]]:
        return self.held_rows

    async def requeue(self, ids: list[uuid.UUID]) -> int:
        self.requeued += ids
        return len(ids)

    async def member_rows(self, collection_id: uuid.UUID) -> list[Any]:
        return [(uuid.uuid4(), DownloadStatus.PENDING, 0, None) for _ in self.entries]

    async def watched_counts(self, ids: list[uuid.UUID]) -> dict[uuid.UUID, int]:
        return dict.fromkeys(ids, 0)

    async def downloads_page(self, collection_id: uuid.UUID, page: int, page_size: int) -> tuple[list[Any], Meta]:
        return list(self.page), Meta(page=page, page_size=page_size, total=len(self.page), total_pages=1)

    async def pause(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        self.calls.append(("pause", collection_id))
        return list(self.running)

    async def resume(self, collection_id: uuid.UUID) -> int:
        self.calls.append(("resume", collection_id))
        return 0

    async def remove(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        self.calls.append(("remove", collection_id))
        return list(self.running)

    async def soft_delete(self, collection: Any) -> None:
        self.deleted = True


def request(count: int = 3, *, channel_tab: bool = False, preset: Preset = Preset.P1080) -> CollectionRequest:
    return CollectionRequest(
        url="https://www.youtube.com/playlist?list=PL1",
        extractor="YoutubeTab",
        external_id="PL1",
        title="29C3: Not my department",
        channel_tab=channel_tab,
        preset=preset,
        entries=[
            CollectionEntryRequest(index=n, id=f"v{n}", url=f"https://youtu.be/v{n}", title=f"Talk {n}")
            for n in range(1, count + 1)
        ],
    )


def service(repo: FakeCollections, root: Path) -> CollectionService:
    hub, live = RecordingHub(), LiveStats()
    return CollectionService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        segment_repo=None,
        control=DownloadControl(),
        downloads_root=root,
        totals=CollectionTotals(repo, hub, live),  # ty: ignore[invalid-argument-type]
        views=memory_views(live=live),
    )


def held_collection(repo: FakeCollections, root: Path) -> Any:
    repo.collection = a_site_row(
        "https://www.youtube.com/playlist?list=PL1",
        provider="Youtube",
        title="29C3: Not my department",
        kind=MediaKind.PLAYLIST,
        preset=Preset.P1080,
        id=uuid.uuid4(),
        created_at=None,
    )
    (root / "29C3_ Not my department [PL1]").mkdir()
    return repo.collection


@pytest.mark.asyncio
async def test_a_new_listing_becomes_a_numbered_collection_in_its_own_folder(tmp_path: Path) -> None:
    repo = FakeCollections()

    schema = await service(repo, tmp_path).add(request(12))

    assert repo.created is not None
    assert (repo.created["media"]["kind"], repo.created["provider"], repo.created["url"]) == (
        MediaKind.PLAYLIST,
        "Youtube",
        "https://www.youtube.com/playlist?list=PL1",
    )
    assert repo.created["collection"] == {"status": DownloadStatus.PENDING}  # the folder is derived, never stored
    assert schema.folder == "29C3_ Not my department [PL1]"
    assert (tmp_path / "29C3_ Not my department [PL1]").is_dir()
    first = repo.entries[0]
    assert first.url == "https://youtu.be/v1"
    assert first.media == {"title": "Talk 1", "kind": MediaKind.VIDEO, "preset": Preset.P1080, "playlist_index": 1}
    assert [e.filename for e in repo.entries[:2]] == ["01_", "02_"]
    assert schema.type == "collection"
    assert schema.counts.total == 12


@pytest.mark.asyncio
async def test_a_channel_tab_is_a_channel_and_is_not_numbered(tmp_path: Path) -> None:
    repo = FakeCollections()

    await service(repo, tmp_path).add(request(2, channel_tab=True))

    assert repo.created is not None and repo.created["media"]["kind"] == MediaKind.CHANNEL
    assert repo.entries[0].filename == ""


@pytest.mark.asyncio
async def test_mp3_videos_are_audio(tmp_path: Path) -> None:
    repo = FakeCollections()

    await service(repo, tmp_path).add(request(1, preset=Preset.MP3))

    assert repo.entries[0].media["kind"] == MediaKind.AUDIO


@pytest.mark.asyncio
async def test_more_than_ten_thousand_videos_are_refused(tmp_path: Path) -> None:
    repo = FakeCollections()

    with pytest.raises(Error) as caught:
        await service(repo, tmp_path).add(request(10_001))

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert repo.created is None


@pytest.mark.asyncio
async def test_adding_a_list_again_joins_its_collection(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)
    repo.held_rows = {
        "v1": (uuid.uuid4(), DownloadStatus.COMPLETED),
        "v2": (uuid.uuid4(), DownloadStatus.COMPLETED),
    }

    joined = await service(repo, tmp_path).add(request(4))

    assert joined.id == collection.id
    assert repo.created is None  # no second collection
    assert [(e.url.rsplit("/", 1)[-1], e.filename) for e in repo.added] == [
        ("v3", "03_"),
        ("v4", "04_"),
    ]


@pytest.mark.asyncio
async def test_joining_numbers_by_arrival(tmp_path: Path) -> None:
    """A tab lists newest first, so a later listing's numbers would collide."""
    repo = FakeCollections()
    held_collection(repo, tmp_path)
    repo.held_rows = {"v3": (uuid.uuid4(), DownloadStatus.COMPLETED)}

    await service(repo, tmp_path).add(request(3, channel_tab=True))

    assert [(e.url.rsplit("/", 1)[-1], e.filename) for e in repo.added] == [
        ("v1", ""),
        ("v2", ""),
    ]


@pytest.mark.asyncio
async def test_joining_resumes_a_ticked_video_that_failed_or_paused(tmp_path: Path) -> None:
    repo = FakeCollections()
    held_collection(repo, tmp_path)
    failed, paused, done = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    repo.held_rows = {
        "v1": (failed, DownloadStatus.FAILED),
        "v2": (paused, DownloadStatus.PAUSED),
        "v3": (done, DownloadStatus.COMPLETED),
    }

    await service(repo, tmp_path).add(request(3))

    assert repo.added == []
    assert sorted(map(str, repo.requeued)) == sorted(map(str, [failed, paused]))


@pytest.mark.asyncio
async def test_a_join_past_ten_thousand_is_refused(tmp_path: Path) -> None:
    repo = FakeCollections()
    held_collection(repo, tmp_path)
    repo.held_rows = {f"x{n}": (uuid.uuid4(), DownloadStatus.COMPLETED) for n in range(1, 9_999)}

    with pytest.raises(Error) as caught:
        await service(repo, tmp_path).add(request(3))

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert repo.added == []


@pytest.mark.asyncio
async def test_a_collection_lists_its_videos(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)
    video = download_row(parent_id=collection.id)
    repo.page = [video]

    rows, meta = await service(repo, tmp_path).downloads_page(collection.id, 1, 50)

    assert [r.id for r in rows] == [video.id]
    assert meta.total == 1


@pytest.mark.asyncio
async def test_an_unknown_collection_is_a_404(tmp_path: Path) -> None:
    with pytest.raises(Error) as caught:
        await service(FakeCollections(), tmp_path).downloads_page(uuid.uuid4(), 1, 50)
    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_pausing_a_collection_pauses_its_videos_and_stops_the_running_ones(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)
    running = uuid.uuid4()
    repo.running = [running]
    svc = service(repo, tmp_path)

    schema = await svc.pause(collection.id)

    assert repo.calls == [("pause", collection.id)]
    assert svc._control.is_stopping(running)
    assert schema.id == collection.id


@pytest.mark.asyncio
async def test_resuming_a_collection_requeues_its_videos(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)

    await service(repo, tmp_path).resume(collection.id)

    assert repo.calls == [("resume", collection.id)]


@pytest.mark.asyncio
async def test_removing_a_collection_with_its_files_deletes_the_folder(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)
    running = uuid.uuid4()
    (tmp_path / str(running)).mkdir()
    repo.running = [running]

    await service(repo, tmp_path).cancel(collection.id, delete_files=True)

    assert repo.calls == [("remove", collection.id)]
    assert not (tmp_path / "29C3_ Not my department [PL1]").exists()
    assert not (tmp_path / str(running)).exists()
    assert repo.deleted


@pytest.mark.asyncio
async def test_removing_a_collection_can_keep_what_finished_even_mid_download(tmp_path: Path) -> None:
    repo = FakeCollections()
    collection = held_collection(repo, tmp_path)
    (tmp_path / "29C3_ Not my department [PL1]" / "01_done.mp4").write_bytes(b"x")

    await service(repo, tmp_path).cancel(collection.id, delete_files=False)

    assert (tmp_path / "29C3_ Not my department [PL1]" / "01_done.mp4").exists()
    assert repo.deleted


@pytest.mark.asyncio
async def test_a_channel_tab_s_folder_is_the_one_every_read_names(tmp_path: Path) -> None:
    """The listing gives a tab its channel's UC id; its address gives its handle, which reads use."""
    repo = FakeCollections()
    tab = request(1, channel_tab=True).model_copy(
        update={"url": "https://www.youtube.com/@TED/videos", "external_id": "UCAuUUnT6oDeKwE6v1NGQxug", "title": "TED"}
    )

    schema = await service(repo, tmp_path).add(tab)

    assert schema.folder == "TED [@TED]"
    assert (tmp_path / schema.folder).is_dir()
