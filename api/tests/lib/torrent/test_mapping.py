from typing import Any

import pytest
from src.data.type import TaskStatus
from src.lib.torrent.mapping import (
    bps_from_mbps,
    eta_from,
    peers_from,
    progress_from_stats,
    progress_percent,
    status_for,
)
from src.lib.torrent.protocol import TorrentProgress

MIB = 1024 * 1024


def _stats(**overrides: Any) -> dict[str, Any]:
    """A live rqbit stats body, in the shape /torrents?with_stats=true returns."""
    base: dict[str, Any] = {
        "state": "live",
        "file_progress": [100, 200],
        "error": None,
        "progress_bytes": 300,
        "uploaded_bytes": 150,
        "total_bytes": 1000,
        "finished": False,
        "live": {
            "snapshot": {
                "downloaded_and_checked_bytes": 300,
                "uploaded_bytes": 150,
                "peer_stats": {"live": 7, "queued": 3, "connecting": 1, "seen": 40, "dead": 2},
            },
            "download_speed": {"mbps": 2.0},
            "upload_speed": {"mbps": 0.5},
            "time_remaining": {"duration": {"secs": 42, "nanos": 0}, "human_readable": "42s"},
        },
    }
    base.update(overrides)
    return base


def test_mbps_is_really_mebibytes_per_second() -> None:
    assert bps_from_mbps({"mbps": 1.0}) == MIB
    assert bps_from_mbps({"mbps": 2.5}) == int(2.5 * MIB)


def test_bare_number_is_accepted() -> None:
    assert bps_from_mbps(1.0) == MIB


def test_missing_speed_is_zero() -> None:
    assert bps_from_mbps(None) == 0
    assert bps_from_mbps({}) == 0


def test_negative_speed_is_clamped() -> None:
    assert bps_from_mbps({"mbps": -1.0}) == 0


def test_eta_reads_the_nested_duration() -> None:
    assert eta_from({"duration": {"secs": 42, "nanos": 0}, "human_readable": "42s"}) == 42


def test_eta_accepts_a_bare_duration() -> None:
    assert eta_from({"secs": 9, "nanos": 0}) == 9


def test_eta_accepts_a_number() -> None:
    assert eta_from(15) == 15


def test_eta_is_none_when_absent() -> None:
    assert eta_from(None) is None
    assert eta_from({}) is None


def test_peers_counts_live_connections_only() -> None:
    assert peers_from({"peer_stats": {"live": 7, "queued": 3, "dead": 2}}) == 7


def test_peers_is_zero_without_a_snapshot() -> None:
    assert peers_from(None) == 0
    assert peers_from({}) == 0


def test_progress_from_stats_converts_everything() -> None:
    progress = progress_from_stats("abc", _stats())
    assert progress.info_hash == "abc"
    assert progress.state == "live"
    assert progress.finished is False
    assert progress.progress_bytes == 300
    assert progress.uploaded_bytes == 150
    assert progress.total_bytes == 1000
    assert progress.download_bps == 2 * MIB
    assert progress.upload_bps == int(0.5 * MIB)
    assert progress.peers_connected == 7
    assert progress.eta_seconds == 42
    assert progress.error is None
    assert progress.file_progress == [100, 200]


def test_progress_from_stats_survives_a_paused_torrent() -> None:
    """A paused torrent has no ``live`` block at all."""
    progress = progress_from_stats("abc", _stats(state="paused", live=None))
    assert progress.state == "paused"
    assert progress.download_bps == 0
    assert progress.upload_bps == 0
    assert progress.peers_connected == 0
    assert progress.eta_seconds is None


def test_progress_from_stats_carries_the_error_text() -> None:
    progress = progress_from_stats("abc", _stats(state="error", live=None, error="disk full"))
    assert progress.state == "error"
    assert progress.error == "disk full"


def test_initializing_state_is_read_from_the_flattened_tag() -> None:
    """rqbit flattens its state enum, so initializing carries an extra key."""
    stats = _stats(state="initializing", initializing_paused=False, live=None)
    assert progress_from_stats("abc", stats).state == "initializing"


def test_progress_percent_rounds_down_and_clamps() -> None:
    assert progress_percent(0, 1000) == 0
    assert progress_percent(999, 1000) == 99
    assert progress_percent(1000, 1000) == 100
    assert progress_percent(1200, 1000) == 100


def test_progress_percent_of_an_unknown_total_is_zero() -> None:
    assert progress_percent(500, 0) == 0


def _progress(state: str, *, finished: bool = False) -> TorrentProgress:
    return TorrentProgress(
        info_hash="abc",
        state=state,
        finished=finished,
        progress_bytes=0,
        uploaded_bytes=0,
        total_bytes=100,
        download_bps=0,
        upload_bps=0,
        peers_connected=0,
    )


@pytest.mark.parametrize(
    ("state", "finished", "expected"),
    [
        ("initializing", False, TaskStatus.DOWNLOADING),
        ("live", False, TaskStatus.DOWNLOADING),
        ("live", True, TaskStatus.SEEDING),
        ("paused", False, TaskStatus.PAUSED),
        ("error", False, TaskStatus.FAILED),
        ("error", True, TaskStatus.FAILED),
    ],
)
def test_status_for(state: str, finished: bool, expected: TaskStatus) -> None:
    assert status_for(_progress(state, finished=finished), TaskStatus.DOWNLOADING) == expected


def test_a_stopped_seed_stays_complete() -> None:
    """Stopping seeding pauses the engine. That pause must not un-complete the row."""
    assert status_for(_progress("paused", finished=True), TaskStatus.COMPLETE) == TaskStatus.COMPLETE


def test_a_canceled_row_is_never_revived() -> None:
    assert status_for(_progress("live"), TaskStatus.CANCELED) == TaskStatus.CANCELED


def test_a_failed_torrent_recovers_when_the_engine_does() -> None:
    assert status_for(_progress("live"), TaskStatus.FAILED) == TaskStatus.DOWNLOADING
