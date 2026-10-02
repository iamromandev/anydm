from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.data.type import DownloadStatus, Platform
from src.lib.torrent import error as torrent_error
from src.lib.torrent.protocol import TorrentProgress
from src.service.download.live import Live, LiveStats
from src.service.download.torrent_monitor import TorrentMonitor

from tests.service.download.memory import MemoryFiles, RecordingHub, download_row, memory_views, torrent_detail

HASH = "abc"


def _row(**overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "source_url": f"magnet:?xt=urn:btih:{HASH}",
        "platform": Platform.TORRENT,
        "title": "Some Release",
        "folder": "torrent/Some Release",
        "torrent_detail": torrent_detail(HASH),
    }
    fields.update(overrides)
    return download_row(**fields)


def _sample(**overrides: Any) -> TorrentProgress:
    fields: dict[str, Any] = {
        "info_hash": HASH,
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

    async def set_rate_limits(self, *, download_bps: int, upload_bps: int) -> None:
        if self.limits_fail:
            raise self.limits_fail
        self.limits.append((download_bps, upload_bps))


def _monitor(
    rows: list[Any],
    client: FakeClient,
    *,
    files: MemoryFiles | None = None,
    hub: RecordingHub | None = None,
    live: LiveStats | None = None,
    root: Path = Path("/workdir/download"),
    download_limit_bps: int = 0,
    upload_limit_bps: int = 0,
) -> TorrentMonitor:
    files = files or MemoryFiles()
    live = live or LiveStats()
    return TorrentMonitor(
        repo=FakeRepo(rows),  # ty: ignore[invalid-argument-type]
        file_repo=files,  # ty: ignore[invalid-argument-type]
        client=client,  # ty: ignore[invalid-argument-type]
        hub=hub or RecordingHub(),  # ty: ignore[invalid-argument-type]
        live=live,
        views=memory_views(files=files, live=live),
        poll_ms=1000,
        downloads_root=root,
        enabled=True,
        download_limit_bps=download_limit_bps,
        upload_limit_bps=upload_limit_bps,
    )


@pytest.mark.asyncio
async def test_tick_mirrors_bytes_onto_the_row_and_live_numbers_into_live_stats() -> None:
    row = _row()
    live = LiveStats()
    await _monitor([row], FakeClient([_sample()]), live=live).tick()

    assert (row.status, row.downloaded_bytes, row.total_bytes) == (DownloadStatus.DOWNLOADING, 500, 1000)
    assert row.torrent_detail.uploaded_bytes == 100
    assert live.get(row.id) == Live(speed_bps=4096, eta_seconds=12, upload_speed_bps=512, peers=6)
    assert all("speed_bps" not in fields for fields in row.saved)


@pytest.mark.asyncio
async def test_tick_writes_per_file_progress() -> None:
    row = _row()
    files = MemoryFiles()
    await _monitor([row], FakeClient([_sample()]), files=files).tick()

    assert files.flushed == [(row.id, [500])]


@pytest.mark.asyncio
async def test_a_finished_torrent_becomes_seeding_and_is_stamped() -> None:
    row = _row(status=DownloadStatus.DOWNLOADING)
    sample = _sample(finished=True, progress_bytes=1000, eta_seconds=None)

    await _monitor([row], FakeClient([sample])).tick()

    assert row.status == DownloadStatus.SEEDING
    assert row.downloaded_bytes == 1000
    assert row.completed_at is not None


@pytest.mark.asyncio
async def test_an_errored_torrent_carries_the_engine_message() -> None:
    row = _row(status=DownloadStatus.DOWNLOADING)
    sample = _sample(state="error", error="no space left on device")

    await _monitor([row], FakeClient([sample])).tick()

    assert row.status == DownloadStatus.FAILED
    assert row.error == "no space left on device"


@pytest.mark.asyncio
async def test_nothing_is_written_when_nothing_changed() -> None:
    row = _row(
        status=DownloadStatus.DOWNLOADING,
        downloaded_bytes=500,
        total_bytes=1000,
        torrent_detail=torrent_detail(HASH, uploaded_bytes=100),
    )

    await _monitor([row], FakeClient([_sample()])).tick()

    assert row.saved == []
    assert row.torrent_detail.saved == []


@pytest.mark.asyncio
async def test_each_tick_sends_a_progress_frame_and_a_snapshot_only_on_a_status_change() -> None:
    row = _row(status=DownloadStatus.DOWNLOADING)
    hub = RecordingHub()
    monitor = _monitor([row], FakeClient([_sample()]), hub=hub)

    await monitor.tick()
    await monitor.tick()

    assert [name for name, _ in hub.events] == ["progress", "progress"]
    frame = hub.named("progress")[0]
    assert frame["live"]["peers"] == 6
    assert frame["files"] == [{"index": 0, "downloaded_bytes": 500}]


@pytest.mark.asyncio
async def test_a_status_change_publishes_a_snapshot() -> None:
    row = _row(status=DownloadStatus.PENDING)
    hub = RecordingHub()

    await _monitor([row], FakeClient([_sample()]), hub=hub).tick()

    (snapshot,) = hub.named("download")
    assert snapshot["status"] == "downloading"
    assert snapshot["torrent"]["info_hash"] == HASH


@pytest.mark.asyncio
async def test_an_unreachable_engine_leaves_rows_untouched() -> None:
    row = _row(status=DownloadStatus.DOWNLOADING)
    client = FakeClient(fail=torrent_error.engine_unavailable("connection refused"))

    await _monitor([row], client).tick()

    assert row.status == DownloadStatus.DOWNLOADING
    assert row.saved == []


@pytest.mark.asyncio
async def test_a_row_the_engine_has_lost_is_re_added_into_its_folder_with_its_selection(tmp_path: Path) -> None:
    row = _row(status=DownloadStatus.DOWNLOADING)
    client = FakeClient([])
    files = MemoryFiles()
    await files.replace(row.id, [(0, "a", 1, True), (1, "b", 1, False), (2, "c", 1, True)])

    await _monitor([row], client, files=files, root=tmp_path).tick()

    assert client.added == [
        {"only_files": [0, 2], "output_folder": str((tmp_path / "torrent" / "Some Release").resolve())}
    ]


@pytest.mark.asyncio
async def test_re_adding_happens_once_not_every_tick() -> None:
    """Reconciliation is a startup job. A later tick must not re-add again."""
    client = FakeClient([])
    monitor = _monitor([_row(status=DownloadStatus.DOWNLOADING)], client)

    await monitor.tick()
    await monitor.tick()

    assert len(client.added) == 1


@pytest.mark.asyncio
async def test_a_row_the_engine_still_has_is_adopted_not_re_added() -> None:
    client = FakeClient([_sample()])

    await _monitor([_row(status=DownloadStatus.DOWNLOADING)], client).tick()

    assert client.added == []


@pytest.mark.asyncio
async def test_the_first_tick_that_reaches_the_engine_pushes_the_limits_once() -> None:
    client = FakeClient([_sample()])
    monitor = _monitor([_row()], client, download_limit_bps=262144, upload_limit_bps=65536)

    await monitor.tick()
    await monitor.tick()

    assert client.limits == [(262144, 65536)]


@pytest.mark.asyncio
async def test_unlimited_is_pushed_too_so_an_old_cap_is_cleared() -> None:
    client = FakeClient([_sample()])

    await _monitor([_row()], client).tick()

    assert client.limits == [(0, 0)]


@pytest.mark.asyncio
async def test_the_limits_are_pushed_again_after_the_engine_comes_back() -> None:
    # rqbit holds limits in memory only, so a restart forgets them.
    client = FakeClient([_sample()])
    monitor = _monitor([_row()], client, upload_limit_bps=65536)

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

    await _monitor([row], client, download_limit_bps=1024).tick()

    assert row.downloaded_bytes == 500
