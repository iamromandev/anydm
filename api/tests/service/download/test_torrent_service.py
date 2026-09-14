import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code
from src.lib.event import EventHub
from src.lib.torrent.protocol import TorrentDetails, TorrentFileInfo
from src.service.download.torrent_service import TorrentService

MAGNET = "magnet:?xt=urn:btih:abc123"

DETAILS = TorrentDetails(
    info_hash="abc123",
    name="Some Release",
    output_folder="/workdir/download/torrent/Some Release",
    files=[
        TorrentFileInfo(index=0, path="video.mkv", size_bytes=900),
        TorrentFileInfo(index=1, path="readme.txt", size_bytes=100),
    ],
)


class FakeTorrentClient:
    def __init__(self, *, details: TorrentDetails = DETAILS, fail: Error | None = None) -> None:
        self.details = details
        self.fail = fail
        self.resolved: list[Any] = []
        self.added: list[dict[str, Any]] = []

    async def ping(self) -> bool:
        return self.fail is None

    async def resolve(self, source: Any) -> TorrentDetails:
        if self.fail:
            raise self.fail
        self.resolved.append(source)
        return self.details

    async def add(self, source: Any, *, only_files: Any, output_folder: str) -> TorrentDetails:
        if self.fail:
            raise self.fail
        self.added.append(
            {"source": source, "only_files": list(only_files), "output_folder": output_folder}
        )
        return self.details

    async def list_progress(self) -> list[Any]:
        return []

    async def pause(self, info_hash: str) -> None: ...

    async def start(self, info_hash: str) -> None: ...

    async def delete(self, info_hash: str) -> None: ...


class FakeTaskRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        kwargs.setdefault("downloaded_bytes", 0)
        kwargs.setdefault("speed_bps", 0)
        kwargs.setdefault("eta_seconds", None)
        kwargs.setdefault("uploaded_bytes", 0)
        kwargs.setdefault("peers_connected", 0)
        kwargs.setdefault("file_size", None)
        kwargs.setdefault("error", None)
        kwargs.setdefault("error_code", None)
        kwargs.setdefault("attempts", 0)
        kwargs.setdefault("started_at", None)
        kwargs.setdefault("completed_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()


class FakeFileRepo:
    def __init__(self) -> None:
        self.replaced: dict[uuid.UUID, list[tuple[int, str, int, bool]]] = {}

    async def replace(self, task_id: uuid.UUID, files: Any) -> None:
        self.replaced[task_id] = list(files)

    async def list_for(self, task_id: uuid.UUID) -> list[Any]:
        return []

    async def selected_indexes(self, task_id: uuid.UUID) -> list[int]:
        return [index for index, _, _, selected in self.replaced.get(task_id, []) if selected]

    async def flush_progress(self, task_id: uuid.UUID, file_progress: Any) -> None: ...

    async def selected_size(self, task_id: uuid.UUID) -> int:
        return sum(size for _, _, size, selected in self.replaced.get(task_id, []) if selected)


def _service(
    client: FakeTorrentClient | None = None,
    *,
    repo: FakeTaskRepo | None = None,
    file_repo: FakeFileRepo | None = None,
    enabled: bool = True,
) -> TorrentService:
    return TorrentService(
        repo=repo or FakeTaskRepo(),  # ty: ignore[invalid-argument-type]
        file_repo=file_repo or FakeFileRepo(),  # ty: ignore[invalid-argument-type]
        client=client or FakeTorrentClient(),
        hub=EventHub(),
        torrent_root=Path("/workdir/download/torrent"),
        enabled=enabled,
    )


@pytest.mark.asyncio
async def test_resolve_returns_the_file_list_without_creating_a_task() -> None:
    repo = FakeTaskRepo()
    response = await _service(repo=repo).resolve(MAGNET)

    assert response.info_hash == "abc123"
    assert response.title == "Some Release"
    assert response.total_bytes == 1000
    assert [f.index for f in response.files] == [0, 1]
    assert response.files[0].path == "video.mkv"
    assert repo.created == []


@pytest.mark.asyncio
async def test_resolve_preselects_every_file() -> None:
    response = await _service().resolve(MAGNET)
    assert all(f.selected for f in response.files)


@pytest.mark.asyncio
async def test_resolve_rejects_input_that_is_not_a_torrent() -> None:
    client = FakeTorrentClient()
    with pytest.raises(Error) as caught:
        await _service(client).resolve("not a torrent!!")

    assert caught.value.code == Code.BAD_REQUEST
    assert client.resolved == []


@pytest.mark.asyncio
async def test_resolve_is_unavailable_when_torrents_are_disabled() -> None:
    with pytest.raises(Error) as caught:
        await _service(enabled=False).resolve(MAGNET)
    assert caught.value.code == Code.SERVICE_UNAVAILABLE


@pytest.mark.asyncio
async def test_resolve_surfaces_an_engine_failure_unchanged() -> None:
    from src.lib.torrent import error as torrent_error

    client = FakeTorrentClient(fail=torrent_error.metadata_timeout(30))
    with pytest.raises(Error) as caught:
        await _service(client).resolve(MAGNET)
    assert caught.value.code == Code.REQUEST_TIMEOUT
