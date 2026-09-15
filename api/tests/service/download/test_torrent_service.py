import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.torrent.protocol import FileInfo, TorrentDetails
from src.service.download.torrent_service import TorrentService

MAGNET = "magnet:?xt=urn:btih:abc123"

DETAILS = TorrentDetails(
    info_hash="abc123",
    name="Some Release",
    output_folder="/workdir/download/torrent/Some Release",
    files=[
        FileInfo(index=0, path="video.mkv", size_bytes=900),
        FileInfo(index=1, path="readme.txt", size_bytes=100),
    ],
)


class FakeTorrentClient:
    def __init__(self, *, details: TorrentDetails = DETAILS, fail: Error | None = None) -> None:
        self.details = details
        self.fail = fail
        self.resolved: list[Any] = []
        self.added: list[dict[str, Any]] = []
        self.paused: list[str] = []
        self.started: list[str] = []
        self.deleted: list[str] = []

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

    async def pause(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.paused.append(info_hash)

    async def start(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.started.append(info_hash)

    async def delete(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.deleted.append(info_hash)


class FakeTaskRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.rows: dict[uuid.UUID, Any] = {}

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

    async def get_active_by_id(self, task_id: uuid.UUID) -> Any:
        return self.rows.get(task_id)


class FakeFileRepo:
    def __init__(self) -> None:
        self.replaced: dict[uuid.UUID, list[tuple[int, str, int, bool]]] = {}
        self.rows: list[Any] = []

    async def replace(self, task_id: uuid.UUID, files: Any) -> None:
        self.replaced[task_id] = list(files)

    async def list_for(self, task_id: uuid.UUID) -> list[Any]:
        return self.rows

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


@pytest.mark.asyncio
async def test_enqueue_adds_to_the_engine_and_creates_a_task() -> None:
    client = FakeTorrentClient()
    repo = FakeTaskRepo()
    files = FakeFileRepo()

    task = await _service(client, repo=repo, file_repo=files).enqueue(MAGNET, [0])

    assert client.added[0]["only_files"] == [0]
    assert client.added[0]["output_folder"] == "/workdir/download/torrent"

    created = repo.created[0]
    assert created["platform"].value == "torrent"
    assert created["kind"].value == "torrent"
    assert created["status"].value == "pending"
    assert created["info_hash"] == "abc123"
    assert created["title"] == "Some Release"
    assert created["source_url"] == MAGNET
    # Only the selected file counts towards the size the UI shows.
    assert created["total_bytes"] == 900
    assert task.info_hash == "abc123"


@pytest.mark.asyncio
async def test_enqueue_records_which_files_were_chosen() -> None:
    files = FakeFileRepo()
    task = await _service(file_repo=files).enqueue(MAGNET, [1])

    rows = files.replaced[task.id]
    assert rows == [(0, "video.mkv", 900, False), (1, "readme.txt", 100, True)]


@pytest.mark.asyncio
async def test_an_empty_selection_means_every_file() -> None:
    client = FakeTorrentClient()
    files = FakeFileRepo()

    task = await _service(client, file_repo=files).enqueue(MAGNET, [])

    assert client.added[0]["only_files"] == []
    assert [selected for _, _, _, selected in files.replaced[task.id]] == [True, True]


@pytest.mark.asyncio
async def test_a_selection_naming_no_real_file_is_rejected() -> None:
    client = FakeTorrentClient()
    with pytest.raises(Error) as caught:
        await _service(client).enqueue(MAGNET, [7])

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert client.added == []


@pytest.mark.asyncio
async def test_a_torrent_file_upload_stores_a_magnet_for_its_info_hash() -> None:
    """A base64 .torrent must not be written into source_url.

    Reconciliation re-adds a lost torrent from this column, and an info-hash
    magnet is both small and re-addable. The original blob is neither.
    """
    import base64

    repo = FakeTaskRepo()
    encoded = base64.b64encode(b"d8:announce1:xe").decode()

    await _service(repo=repo).enqueue(encoded, [0])

    assert repo.created[0]["source_url"] == "magnet:?xt=urn:btih:abc123"


@pytest.mark.asyncio
async def test_enqueue_publishes_the_new_task() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    service = TorrentService(
        repo=FakeTaskRepo(),  # ty: ignore[invalid-argument-type]
        file_repo=FakeFileRepo(),  # ty: ignore[invalid-argument-type]
        client=FakeTorrentClient(),
        hub=hub,
        torrent_root=Path("/workdir/download/torrent"),
        enabled=True,
    )

    await service.enqueue(MAGNET, [0])

    event, data = await anext(aiter(subscription))
    assert event == "task"
    assert data["info_hash"] == "abc123"
    subscription.close()


@pytest.mark.asyncio
async def test_enqueue_is_unavailable_when_torrents_are_disabled() -> None:
    with pytest.raises(Error) as caught:
        await _service(enabled=False).enqueue(MAGNET, [0])
    assert caught.value.code == Code.SERVICE_UNAVAILABLE


def _torrent_row(task_id: uuid.UUID, **overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "id": task_id,
        "source_url": MAGNET,
        "platform": Platform.TORRENT,
        "video_id": None,
        "preset": Preset.BEST,
        "kind": Kind.TORRENT,
        "title": "Some Release",
        "filename": "Some Release",
        "mime_type": None,
        "info_hash": "abc123",
        "status": TaskStatus.DOWNLOADING,
        "progress": 40,
        "downloaded_bytes": 400,
        "total_bytes": 1000,
        "speed_bps": 100,
        "eta_seconds": 10,
        "uploaded_bytes": 0,
        "peers_connected": 3,
        "file_path": "/workdir/download/torrent/Some Release",
        "file_size": None,
        "error": None,
        "error_code": None,
        "attempts": 0,
        "next_attempt_at": None,
        "deleted_at": None,
        "created_at": None,
        "started_at": None,
        "completed_at": None,
    }
    fields.update(overrides)
    row = type("Row", (), fields)()

    async def _save(*_args: Any, **_kwargs: Any) -> None:
        return None

    row.save = _save
    return row


@pytest.mark.asyncio
async def test_pause_pauses_the_engine_and_the_row() -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.DOWNLOADING)
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo).pause(task_id)

    assert client.paused == ["abc123"]
    assert schema.status == TaskStatus.PAUSED
    assert schema.speed_bps == 0


@pytest.mark.asyncio
async def test_resume_starts_the_engine_and_clears_the_error() -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.FAILED, error="boom")
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo).resume(task_id)

    assert client.started == ["abc123"]
    assert schema.status == TaskStatus.DOWNLOADING
    assert schema.error is None


@pytest.mark.asyncio
async def test_stop_seeding_pauses_the_engine_and_completes_the_row() -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.SEEDING, progress=100)
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo).stop_seeding(task_id)

    assert client.paused == ["abc123"]
    assert schema.status == TaskStatus.COMPLETE


@pytest.mark.asyncio
async def test_stop_seeding_refuses_a_task_that_is_not_seeding() -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await _service(repo=repo).stop_seeding(task_id)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_cancel_deletes_from_the_engine_and_soft_deletes_the_row() -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    row = _torrent_row(task_id, status=TaskStatus.SEEDING)
    repo.rows[task_id] = row
    client = FakeTorrentClient()

    await _service(client, repo=repo).cancel(task_id)

    assert client.deleted == ["abc123"]
    assert row.status == TaskStatus.CANCELED
    assert row.deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_still_soft_deletes_when_the_engine_is_gone() -> None:
    """The user asked for it gone. An unreachable engine must not block that."""
    from src.data.type import TaskStatus
    from src.lib.torrent import error as torrent_error

    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    row = _torrent_row(task_id, status=TaskStatus.DOWNLOADING)
    repo.rows[task_id] = row
    client = FakeTorrentClient(fail=torrent_error.engine_unavailable("refused"))

    await _service(client, repo=repo).cancel(task_id)

    assert row.status == TaskStatus.CANCELED


@pytest.mark.asyncio
async def test_resolve_file_returns_the_path_for_an_index(tmp_path: Path) -> None:
    task_id = uuid.uuid4()
    folder = tmp_path / "Some Release"
    folder.mkdir()
    (folder / "video.mkv").write_bytes(b"data")

    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(
        task_id, status=TaskStatus.SEEDING, file_path=str(folder)
    )
    files = FakeFileRepo()
    files.rows = [
        type("F", (), {"index": 0, "path": "video.mkv", "selected": True})(),
        type("F", (), {"index": 1, "path": "readme.txt", "selected": False})(),
    ]

    path, filename, media_type = await _service(repo=repo, file_repo=files).resolve_file(task_id, 0)

    assert path == folder / "video.mkv"
    assert filename == "video.mkv"
    assert media_type == "video/x-matroska"


@pytest.mark.asyncio
async def test_resolve_file_refuses_a_file_that_was_not_selected(tmp_path: Path) -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.SEEDING, file_path=str(tmp_path))
    files = FakeFileRepo()
    files.rows = [type("F", (), {"index": 1, "path": "readme.txt", "selected": False})()]

    with pytest.raises(Error) as caught:
        await _service(repo=repo, file_repo=files).resolve_file(task_id, 1)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_resolve_file_404s_on_an_unknown_index(tmp_path: Path) -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.SEEDING, file_path=str(tmp_path))
    files = FakeFileRepo()
    files.rows = []

    with pytest.raises(Error) as caught:
        await _service(repo=repo, file_repo=files).resolve_file(task_id, 9)
    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_resolve_file_409s_while_the_torrent_is_still_downloading(tmp_path: Path) -> None:
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(
        task_id, status=TaskStatus.DOWNLOADING, file_path=str(tmp_path)
    )

    with pytest.raises(Error) as caught:
        await _service(repo=repo).resolve_file(task_id, 0)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_resolve_file_refuses_a_path_escaping_the_output_folder(tmp_path: Path) -> None:
    """A torrent's file names come from a stranger. They do not get to escape."""
    task_id = uuid.uuid4()
    repo = FakeTaskRepo()
    repo.rows[task_id] = _torrent_row(task_id, status=TaskStatus.SEEDING, file_path=str(tmp_path))
    files = FakeFileRepo()
    files.rows = [type("F", (), {"index": 0, "path": "../../etc/passwd", "selected": True})()]

    with pytest.raises(Error) as caught:
        await _service(repo=repo, file_repo=files).resolve_file(task_id, 0)
    assert caught.value.code == Code.NOT_FOUND
