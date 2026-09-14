from typing import Any

from src.service.download.progress import ProgressAggregator, ProgressTracker
from src.service.download.segment import plan_segments


def _tracker(**overrides: Any) -> ProgressTracker:
    base: dict[str, Any] = {"total_bytes": 1000, "flush_interval_ms": 1000, "started_at": 0.0}
    base.update(overrides)
    return ProgressTracker(**base)


def test_record_returns_nothing_before_the_interval_elapses() -> None:
    tracker = _tracker()
    assert tracker.record(100, at=0.5) is None
    assert tracker.record(100, at=0.9) is None


def test_record_emits_once_the_interval_elapses() -> None:
    tracker = _tracker()
    tracker.record(100, at=0.5)
    sample = tracker.record(400, at=1.0)
    assert sample is not None
    assert sample.downloaded_bytes == 500
    assert sample.progress == 50


def test_progress_is_capped_at_100() -> None:
    tracker = _tracker(total_bytes=100)
    sample = tracker.record(250, at=1.0)
    assert sample is not None
    assert sample.progress == 100


def test_progress_is_zero_without_a_known_total() -> None:
    tracker = _tracker(total_bytes=None)
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.progress == 0
    assert sample.eta_seconds is None


def test_first_sample_seeds_the_speed_rather_than_smoothing_from_zero() -> None:
    tracker = _tracker()
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.speed_bps == 500


def test_later_samples_are_smoothed() -> None:
    tracker = _tracker(total_bytes=100_000, alpha=0.5)
    tracker.record(500, at=1.0)  # seeds at 500 B/s
    sample = tracker.record(1500, at=2.0)  # instant 1500 B/s
    assert sample is not None
    assert sample.speed_bps == 1000  # 0.5 * 1500 + 0.5 * 500


def test_eta_uses_the_smoothed_speed() -> None:
    tracker = _tracker(total_bytes=2000)
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.eta_seconds == 3  # 1500 remaining at 500 B/s


def test_eta_is_none_when_stalled() -> None:
    tracker = _tracker()
    sample = tracker.record(0, at=1.0)
    assert sample is not None
    assert sample.eta_seconds is None


def test_resuming_counts_the_bytes_already_on_disk() -> None:
    tracker = _tracker(total_bytes=1000, initial_bytes=400)
    sample = tracker.record(100, at=1.0)
    assert sample is not None
    assert sample.downloaded_bytes == 500
    assert sample.progress == 50
    # Only the 100 new bytes count toward speed; the 400 came from a prior run.
    assert sample.speed_bps == 100


def test_snapshot_emits_regardless_of_the_interval() -> None:
    tracker = _tracker()
    sample = tracker.snapshot(at=0.1)
    assert sample is not None
    assert sample.downloaded_bytes == 0


def _aggregator(total: int = 1000, count: int = 4, flush_ms: int = 1000) -> ProgressAggregator:
    return ProgressAggregator(plan_segments(total, count), flush_interval_ms=flush_ms, started_at=0.0)


def test_the_throttle_withholds_samples_until_the_interval_passes() -> None:
    aggregator = _aggregator()
    assert aggregator.record(0, downloaded=10, speed_bps=5, at=0.5) is None
    assert aggregator.record(0, downloaded=20, speed_bps=5, at=1.5) is not None


def test_bytes_and_speeds_sum_across_segments() -> None:
    aggregator = _aggregator()
    aggregator.record(0, downloaded=100, speed_bps=10, at=0.1)
    aggregator.record(1, downloaded=50, speed_bps=20, at=0.2)
    sample = aggregator.snapshot(at=1.1)
    assert sample.downloaded_bytes == 150
    assert sample.speed_bps == 30
    assert sample.total_bytes == 1000
    assert sample.progress == 15


def test_a_stale_sibling_still_counts() -> None:
    """Segments report on their own timers, so the aggregate always mixes a
    fresh sample with slightly older ones. Dropping the old ones would make the
    total lurch backwards."""
    aggregator = _aggregator()
    aggregator.record(0, downloaded=100, speed_bps=10, at=0.1)
    sample = aggregator.record(1, downloaded=25, speed_bps=5, at=1.1)
    assert sample is not None
    assert sample.downloaded_bytes == 125


def test_every_segment_is_reported_even_before_it_starts() -> None:
    aggregator = _aggregator()
    sample = aggregator.snapshot(at=1.1)
    assert [segment.index for segment in sample.segments] == [0, 1, 2, 3]
    assert sample.segments[0].start == 0
    assert sample.segments[0].end == 249
    assert all(segment.downloaded == 0 for segment in sample.segments)


def test_eta_is_none_when_nothing_is_moving() -> None:
    aggregator = _aggregator()
    assert aggregator.snapshot(at=1.1).eta_seconds is None


def test_eta_divides_the_remainder_by_the_combined_speed() -> None:
    aggregator = _aggregator()
    aggregator.record(0, downloaded=200, speed_bps=100, at=0.1)
    aggregator.record(1, downloaded=0, speed_bps=100, at=0.2)
    assert aggregator.snapshot(at=1.1).eta_seconds == 4


def test_progress_never_exceeds_one_hundred() -> None:
    aggregator = _aggregator(total=100, count=2)
    aggregator.record(0, downloaded=50, speed_bps=1, at=0.1)
    aggregator.record(1, downloaded=50, speed_bps=1, at=0.2)
    assert aggregator.snapshot(at=1.1).progress == 100
