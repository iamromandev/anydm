import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.torrent import error as torrent_error
from src.lib.torrent.protocol import TorrentProgress
from src.service.download.torrent_monitor import TorrentMonitor


def _row(**overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "id": uuid.uuid4(),
        "source_url": "magnet:?xt=urn:btih:abc",
        "platform": Platform.TORRENT,
        "video_id": None,
        "preset": Preset.BEST,
        "kind": Kind.TORRENT,
        "title": "Some Release",
        "filename": "Some Release",
        "mime_type": None,
        "info_hash": "abc",
        "status": TaskStatus.PENDING,
        "progress": 0,
        "downloaded_bytes": 0,
        "total_bytes": None,
        "speed_bps": 0,
        "upload_speed_bps": 0,
        "eta_seconds": None,
        "uploaded_bytes": 0,
        "peers_connected": 0,
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
    row.saved_fields = []

    async def _save(*_args: Any, update_fields: Any = None, **_kwargs: Any) -> None:
        row.saved_fields.append(list(update_fields or []))

    row.save = _save
    return row


def _sample(**overrides: Any) -> TorrentProgress:
    fields: dict[str, Any] = {
        "info_hash": "abc",
        "state": "live",
        "finished": False,
        "progress_bytes": 500,
        "uploaded_bytes": 100,
        "total_bytes": 1000,
        "download_bps": 4096,
        "upload_bps": 512,
        "peers_connected": 6,
        "eta_seconds": 12,
        "error": None,
        "file_progress": [500],
    }
    fields.update(overrides)
    return TorrentProgress(**fields)


class FakeRepo:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def torrents_to_watch(self) -> list[Any]:
        return list(self.rows)


class FakeFileRepo:
    def __init__(self, selected: list[int] | None = None) -> None:
        self.flushed: list[tuple[uuid.UUID, list[int]]] = []
        self.selected = selected or [0]

    async def flush_progress(self, task_id: uuid.UUID, file_progress: Any) -> None:
        self.flushed.append((task_id, list(file_progress)))

    async def list_for(self, task_id: uuid.UUID) -> list[Any]:
        # What the flush above just wrote: one row per reported file.
        progress = next((p for t, p in reversed(self.flushed) if t == task_id), [])
        return [
            type("F", (), {"index": i, "path": f"file{i}", "size_bytes": 1000, "selected": True,
                           "downloaded_bytes": done})()
            for i, done in enumerate(progress)
        ]

    async def selected_indexes(self, task_id: uuid.UUID) -> list[int]:
        return self.selected


class FakeClient:
    def __init__(self, samples: list[TorrentProgress] | None = None, *, fail: Error | None = None) -> None:
        self.samples = samples or []
        self.fail = fail
        self.added: list[dict[str, Any]] = []
        self.limits: list[tuple[int, int]] = []
        self.limits_fail: Error | None = None

    async def ping(self) -> bool:
        return self.fail is None

    async def list_progress(self) -> list[TorrentProgress]:
        if self.fail:
            raise self.fail
        return list(self.samples)

    async def add(self, source: Any, *, only_files: Any, output_folder: str) -> Any:
        self.added.append({"only_files": list(only_files), "output_folder": output_folder})
        return None

    async def resolve(self, source: Any) -> Any: ...

    async def set_rate_limits(self, *, download_bps: int, upload_bps: int) -> None:
        if self.limits_fail:
            raise self.limits_fail
        self.limits.append((download_bps, upload_bps))

    async def pause(self, info_hash: str) -> None: ...

    async def start(self, info_hash: str) -> None: ...

    async def delete(self, info_hash: str) -> None: ...


def _monitor(
    repo: Any,
    client: Any,
    file_repo: Any = None,
    hub: EventHub | None = None,
    *,
    download_limit_bps: int = 0,
    upload_limit_bps: int = 0,
    torrent_root: str = "/workdir/download/torrent",
) -> TorrentMonitor:
    return TorrentMonitor(
        repo=repo,
        file_repo=file_repo or FakeFileRepo(),  # ty: ignore[invalid-argument-type]
        client=client,
        hub=hub or EventHub(),
        poll_ms=1000,
        torrent_root=torrent_root,
        enabled=True,
        download_limit_bps=download_limit_bps,
        upload_limit_bps=upload_limit_bps,
    )


@pytest.mark.asyncio
async def test_tick_mirrors_a_sample_onto_the_row() -> None:
    row = _row()
    monitor = _monitor(FakeRepo([row]), FakeClient([_sample()]))

    await monitor.tick()

    assert row.status == TaskStatus.DOWNLOADING
    assert row.progress == 50
    assert row.downloaded_bytes == 500
    assert row.total_bytes == 1000
    assert row.speed_bps == 4096
    assert row.upload_speed_bps == 512
    assert row.uploaded_bytes == 100
    assert row.peers_connected == 6
    assert row.eta_seconds == 12


@pytest.mark.asyncio
async def test_tick_writes_per_file_progress() -> None:
    row = _row()
    files = FakeFileRepo()
    await _monitor(FakeRepo([row]), FakeClient([_sample()]), files).tick()

    assert files.flushed == [(row.id, [500])]


@pytest.mark.asyncio
async def test_a_finished_torrent_becomes_seeding_and_is_stamped() -> None:
    row = _row(status=TaskStatus.DOWNLOADING)
    sample = _sample(finished=True, progress_bytes=1000, eta_seconds=None)

    await _monitor(FakeRepo([row]), FakeClient([sample])).tick()

    assert row.status == TaskStatus.SEEDING
    assert row.progress == 100
    assert row.completed_at is not None


@pytest.mark.asyncio
async def test_an_errored_torrent_carries_the_engine_message() -> None:
    row = _row(status=TaskStatus.DOWNLOADING)
    sample = _sample(state="error", error="no space left on device")

    await _monitor(FakeRepo([row]), FakeClient([sample])).tick()

    assert row.status == TaskStatus.FAILED
    assert row.error == "no space left on device"


@pytest.mark.asyncio
async def test_nothing_is_written_when_nothing_changed() -> None:
    row = _row(
        status=TaskStatus.DOWNLOADING,
        progress=50,
        downloaded_bytes=500,
        total_bytes=1000,
        speed_bps=4096,
        upload_speed_bps=512,
        uploaded_bytes=100,
        peers_connected=6,
        eta_seconds=12,
    )
    monitor = _monitor(FakeRepo([row]), FakeClient([_sample()]))

    await monitor.tick()

    assert row.saved_fields == []


@pytest.mark.asyncio
async def test_every_tick_publishes_even_without_a_write() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    row = _row(status=TaskStatus.DOWNLOADING, progress=50, downloaded_bytes=500,
               total_bytes=1000, speed_bps=4096, uploaded_bytes=100,
               peers_connected=6, eta_seconds=12)

    await _monitor(FakeRepo([row]), FakeClient([_sample()]), hub=hub).tick()

    event, data = await anext(aiter(subscription))
    assert event == "task"
    assert data["peers_connected"] == 6
    subscription.close()


@pytest.mark.asyncio
async def test_every_tick_publishes_the_files_it_just_flushed() -> None:
    """#107: per-file progress reached the database every tick and the browser never."""
    hub = EventHub()
    subscription = hub.subscribe()
    row = _row(status=TaskStatus.DOWNLOADING)
    sample = _sample()

    await _monitor(FakeRepo([row]), FakeClient([sample]), hub=hub).tick()

    _event, data = await anext(aiter(subscription))
    assert [f["downloaded_bytes"] for f in data["files"]] == list(sample.file_progress)
    subscription.close()


@pytest.mark.asyncio
async def test_an_unreachable_engine_leaves_rows_untouched() -> None:
    row = _row(status=TaskStatus.DOWNLOADING)
    client = FakeClient(fail=torrent_error.engine_unavailable("connection refused"))

    await _monitor(FakeRepo([row]), client).tick()

    assert row.status == TaskStatus.DOWNLOADING
    assert row.saved_fields == []


@pytest.mark.asyncio
async def test_a_row_the_engine_has_lost_is_re_added_with_its_selection() -> None:
    row = _row(status=TaskStatus.DOWNLOADING)
    client = FakeClient([])
    files = FakeFileRepo(selected=[0, 2])

    await _monitor(FakeRepo([row]), client, files).tick()

    # Into the folder the row records, not the root every torrent shared before #107.
    assert client.added == [
        {"only_files": [0, 2], "output_folder": "/workdir/download/torrent/Some Release"}
    ]


@pytest.mark.asyncio
async def test_a_torrent_from_before_per_torrent_folders_is_re_added_where_its_files_are(
    tmp_path: Path,
) -> None:
    """Its row records <root>/<name>, but its files were written flat into the root."""
    (tmp_path / "file0").write_bytes(b"data")
    row = _row(status=TaskStatus.DOWNLOADING, file_path=str(tmp_path / "Some Release"))
    client = FakeClient([])
    files = FakeFileRepo(selected=[0])
    files.flushed.append((row.id, [4]))  # so list_for reports file0

    await _monitor(FakeRepo([row]), client, files, torrent_root=str(tmp_path)).tick()

    assert client.added == [{"only_files": [0], "output_folder": str(tmp_path)}]


@pytest.mark.asyncio
async def test_re_adding_happens_once_not_every_tick() -> None:
    """Reconciliation is a startup job. A later tick must not re-add again."""
    row = _row(status=TaskStatus.DOWNLOADING)
    client = FakeClient([])
    monitor = _monitor(FakeRepo([row]), client)

    await monitor.tick()
    await monitor.tick()

    assert len(client.added) == 1


@pytest.mark.asyncio
async def test_a_row_the_engine_still_has_is_adopted_not_re_added() -> None:
    row = _row(status=TaskStatus.DOWNLOADING)
    client = FakeClient([_sample()])

    await _monitor(FakeRepo([row]), client).tick()

    assert client.added == []


@pytest.mark.asyncio
async def test_the_first_tick_that_reaches_the_engine_pushes_the_limits_once() -> None:
    client = FakeClient([_sample()])
    monitor = _monitor(FakeRepo([_row()]), client, download_limit_bps=262144, upload_limit_bps=65536)

    await monitor.tick()
    await monitor.tick()

    assert client.limits == [(262144, 65536)]


@pytest.mark.asyncio
async def test_unlimited_is_pushed_too_so_an_old_cap_is_cleared() -> None:
    client = FakeClient([_sample()])
    monitor = _monitor(FakeRepo([_row()]), client)

    await monitor.tick()

    assert client.limits == [(0, 0)]


@pytest.mark.asyncio
async def test_the_limits_are_pushed_again_after_the_engine_comes_back() -> None:
    # rqbit holds limits in memory only, so a restart forgets them.
    client = FakeClient([_sample()])
    monitor = _monitor(FakeRepo([_row()]), client, upload_limit_bps=65536)

    await monitor.tick()
    client.fail = torrent_error.engine_unavailable("connection refused")
    await monitor.tick()
    client.fail = None
    await monitor.tick()

    assert client.limits == [(0, 65536), (0, 65536)]


@pytest.mark.asyncio
async def test_a_refused_limit_does_not_stop_the_mirroring() -> None:
    row = _row()
    client = FakeClient([_sample()])
    client.limits_fail = torrent_error.engine_rejected("nope")
    monitor = _monitor(FakeRepo([row]), client, download_limit_bps=1024)

    await monitor.tick()

    assert row.downloaded_bytes == 500
