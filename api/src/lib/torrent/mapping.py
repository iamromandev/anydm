"""rqbit's JSON in this project's units. Pure, so every case is cheap to test.

Three of rqbit's shapes are easy to read wrongly, and all three are handled
here rather than at each call site:

* A speed is ``{"mbps": <float>}`` and the name lies. rqbit's own ``as_bytes``
  multiplies it by ``1024 * 1024``, so the unit is mebibytes per second.
* ``time_remaining`` nests a serde ``Duration`` inside a wrapper:
  ``{"duration": {"secs": N, "nanos": N}, "human_readable": "..."}``.
* The ``live`` block is absent entirely for a paused, initializing or errored
  torrent, so every read through it must tolerate ``None``.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from src.lib.torrent.protocol import TorrentProgress

MIB = 1024 * 1024


def bps_from_mbps(value: Any) -> int:
    """A rqbit speed as bytes per second."""
    if isinstance(value, Mapping):
        value = value.get("mbps")
    if value is None:
        return 0
    try:
        return max(0, int(float(value) * MIB))
    except (TypeError, ValueError):
        return 0


def eta_from(value: Any) -> int | None:
    """Seconds remaining, from any of the three shapes this can arrive in."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return max(0, int(value))
    if not isinstance(value, Mapping):
        return None
    duration = value.get("duration", value)
    if not isinstance(duration, Mapping):
        return None
    secs = duration.get("secs")
    return None if secs is None else max(0, int(secs))


def peers_from(snapshot: Any) -> int:
    """Connected peers.

    ``live`` is the count of peers an actual connection is open to. The other
    counters — queued, connecting, seen, dead — are swarm bookkeeping and would
    overstate what is really moving bytes.
    """
    if not isinstance(snapshot, Mapping):
        return 0
    stats = snapshot.get("peer_stats")
    if not isinstance(stats, Mapping):
        return 0
    return max(0, int(stats.get("live") or 0))


def progress_from_stats(info_hash: str, stats: Mapping[str, Any]) -> TorrentProgress:
    """One ``/torrents?with_stats=true`` entry's stats as a ``TorrentProgress``."""
    live = stats.get("live")
    live_map: Mapping[str, Any] = live if isinstance(live, Mapping) else {}
    file_progress = stats.get("file_progress") or []

    return TorrentProgress(
        info_hash=info_hash,
        state=str(stats.get("state") or "initializing"),
        finished=bool(stats.get("finished")),
        progress_bytes=int(stats.get("progress_bytes") or 0),
        uploaded_bytes=int(stats.get("uploaded_bytes") or 0),
        total_bytes=int(stats.get("total_bytes") or 0),
        download_bps=bps_from_mbps(live_map.get("download_speed")),
        upload_bps=bps_from_mbps(live_map.get("upload_speed")),
        peers_connected=peers_from(live_map.get("snapshot")),
        eta_seconds=eta_from(live_map.get("time_remaining")),
        error=stats.get("error") or None,
        file_progress=[int(value) for value in file_progress],
    )


def progress_percent(downloaded: int, total: int) -> int:
    """Whole percent, clamped. Zero when the total is not known yet."""
    if total <= 0:
        return 0
    return max(0, min(100, int(downloaded * 100 // total)))
