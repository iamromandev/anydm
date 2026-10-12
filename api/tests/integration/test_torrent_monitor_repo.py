"""The torrent monitor against Postgres: which rows it watches, what a tick writes, and what reconcile re-adds."""

from pathlib import Path
from typing import Any

import pytest
from src.core.common import now
from src.data.db.model import Download, Torrent
from src.data.repo import DownloadDatabaseRepo, FileDatabaseRepo, PieceDatabaseRepo, PositionDatabaseRepo
from src.data.type import DownloadStatus
from src.lib.event import EventHub
from src.lib.torrent import error as torrent_error
from src.lib.torrent.folder import torrent_folder
from src.lib.torrent.mapping import bitmap_from
from src.lib.torrent.protocol import TorrentProgress
from src.service.download.live import LiveStats
from src.service.download.torrent_monitor import TorrentMonitor
from src.service.download.views import DownloadViews

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

HASH = "d" * 40
MAGNET = f"magnet:?xt=urn:btih:{HASH}"


class Engine:
    """Answers with ``samples``, and records what it is asked to add."""

    def __init__(
        self,
        samples: list[TorrentProgress],
        *,
        haves: dict[str, tuple[bytes, int]] | None = None,
        haves_fail: Any = None,
    ) -> None:
        self.samples = samples
        self.haves_map = haves or {}
        self.haves_fail = haves_fail
        self.added: list[dict[str, Any]] = []

    async def list_progress(self) -> list[TorrentProgress]:
        return list(self.samples)

    async def haves(self, info_hash: str) -> tuple[bytes, int]:
        if self.haves_fail:
            raise self.haves_fail
        return self.haves_map.get(info_hash, (b"", 0))

    async def fetch_metadata(self, info_hash: str) -> bytes:
        raise AssertionError("no backfill expected in this test")

    async def add(self, source: Any, *, only_files: Any, output_folder: str) -> None:
        self.added.append({"source": source, "only_files": list(only_files), "output_folder": output_folder})

    async def set_rate_limits(self, *, download_bps: int, upload_bps: int) -> None:
        return None


def _sample(**overrides: Any) -> TorrentProgress:
    fields: dict[str, Any] = {
        "info_hash": HASH,
        "state": "live",
        "finished": False,
        "progress_bytes": 300,
        "uploaded_bytes": 40,
        "total_bytes": 900,
        "download_bps": 10,
        "upload_bps": 1,
        "peers_connected": 2,
        "eta_seconds": 5,
        "error": None,
        "file_progress": [300, 0],
    }
    fields.update(overrides)
    return TorrentProgress(**fields)


def _monitor(engine: Engine, root: Path) -> TorrentMonitor:
    live = LiveStats()
    return TorrentMonitor(
        repo=DownloadDatabaseRepo(),
        file_repo=FileDatabaseRepo(),
        piece_repo=PieceDatabaseRepo(),
        client=engine,  # ty: ignore[invalid-argument-type]
        hub=EventHub(),
        live=live,
        views=DownloadViews(FileDatabaseRepo(), PositionDatabaseRepo(), live, max_attempts=3),
        poll_ms=1000,
        downloads_root=root,
        enabled=True,
    )


async def _torrent(**download: Any) -> Download:
    return await DownloadDatabaseRepo().create_torrent(
        url=MAGNET,
        info_hash=HASH,
        name="Release",
        total_size=None,
        download={
            "status": DownloadStatus.DOWNLOADING,
            "folder": torrent_folder(Path("."), "Release", HASH).name,
            **download,
        },
        files=[(0, "Release/a.mkv", 900, True), (1, "Release/b.nfo", 1, False)],
    )


@pytest.mark.asyncio
async def test_the_watch_list_is_the_torrents_not_removed() -> None:
    kept = await _torrent()
    await DownloadDatabaseRepo().create_direct(
        url="https://e.com/x.iso", download={"status": DownloadStatus.PENDING}, filename="x.iso"
    )

    assert [row.id for row in await DownloadDatabaseRepo().torrents_to_watch()] == [kept.id]

    await Download.filter(id=kept.id).update(deleted_at=now())
    assert await DownloadDatabaseRepo().torrents_to_watch() == []


@pytest.mark.asyncio
async def test_a_tick_writes_the_sizes_onto_the_download_its_files_and_its_torrent(tmp_path: Path) -> None:
    row = await _torrent()

    await _monitor(Engine([_sample()]), tmp_path).tick()

    await row.refresh_from_db()
    assert (row.downloaded_size, row.total_size, row.uploaded_size) == (300, 900, 40)
    assert (await Torrent.get(info_hash=HASH)).total_size == 900
    files = await FileDatabaseRepo().list_for(row.id)
    assert [file.downloaded_bytes for file in files] == [300, 0]


@pytest.mark.asyncio
async def test_reconcile_re_adds_a_lost_torrent_from_its_magnet_and_selection(tmp_path: Path) -> None:
    await _torrent()
    engine = Engine([])

    await _monitor(engine, tmp_path).tick()

    (added,) = engine.added
    assert added["only_files"] == [0]
    assert added["output_folder"] == str((tmp_path / torrent_folder(Path("."), "Release", HASH).name).resolve())
    assert HASH in str(added["source"]).lower()


@pytest.mark.asyncio
async def test_the_bitmap_follows_the_engine_across_ticks_and_a_restart(tmp_path: Path) -> None:
    row = await _torrent()
    pieces = PieceDatabaseRepo()
    await pieces.ensure(row.id, [(0, 512, "a" * 40), (1, 512, "b" * 40), (2, 88, "c" * 40)])

    await _monitor(Engine([_sample()], haves={HASH: (bitmap_from([0, 2], 3), 3)}), tmp_path).tick()
    assert await pieces.bitmap(row.id) == bitmap_from([0, 2], 3)

    # A new monitor, as after a restart, seeds from what is saved and writes only when more arrives.
    await _monitor(Engine([_sample()], haves={HASH: (bitmap_from([0, 1, 2], 3), 3)}), tmp_path).tick()
    assert await pieces.bitmap(row.id) == bitmap_from([0, 1, 2], 3)

    # Nothing moved: the row is left alone.
    before = (await Download.get(id=row.id)).updated_at
    await _monitor(Engine([_sample()], haves={HASH: (bitmap_from([0, 1, 2], 3), 3)}), tmp_path).tick()
    assert (await Download.get(id=row.id)).updated_at == before


@pytest.mark.asyncio
async def test_a_paused_torrent_keeps_its_saved_bitmap(tmp_path: Path) -> None:
    row = await _torrent(status=DownloadStatus.PAUSED)
    pieces = PieceDatabaseRepo()
    await pieces.ensure(row.id, [(0, 512, "a" * 40), (1, 512, "b" * 40)])
    await pieces.save_bitmap(row.id, bitmap_from([0], 2))

    await _monitor(
        Engine([_sample(state="paused")], haves_fail=torrent_error.engine_rejected("torrent is not live")),
        tmp_path,
    ).tick()

    assert await pieces.bitmap(row.id) == bitmap_from([0], 2)
