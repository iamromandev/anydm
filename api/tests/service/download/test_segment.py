from itertools import pairwise

from src.service.download.segment import Segment, plan_segments, should_segment


def test_an_even_split_covers_every_byte_exactly_once() -> None:
    plan = plan_segments(1000, 4)
    assert plan == [
        Segment(0, 0, 249),
        Segment(1, 250, 499),
        Segment(2, 500, 749),
        Segment(3, 750, 999),
    ]


def test_the_remainder_goes_to_the_last_segment() -> None:
    plan = plan_segments(1003, 4)
    assert [segment.length for segment in plan] == [250, 250, 250, 253]
    assert plan[-1].end == 1002


def test_the_plan_is_gapless_and_never_overlaps() -> None:
    plan = plan_segments(9_999_999, 7)
    assert plan[0].start == 0
    assert plan[-1].end == 9_999_998
    for earlier, later in pairwise(plan):
        assert later.start == earlier.end + 1
    assert sum(segment.length for segment in plan) == 9_999_999


def test_one_segment_is_the_whole_file() -> None:
    assert plan_segments(500, 1) == [Segment(0, 0, 499)]


def test_more_segments_than_bytes_collapses_to_one_per_byte() -> None:
    plan = plan_segments(3, 8)
    assert len(plan) == 3
    assert [segment.length for segment in plan] == [1, 1, 1]


def test_should_segment_needs_range_support() -> None:
    assert not should_segment(total_bytes=100_000_000, accepts_ranges=False, count=4, min_bytes=1)


def test_should_segment_needs_a_known_size() -> None:
    assert not should_segment(total_bytes=None, accepts_ranges=True, count=4, min_bytes=1)


def test_should_segment_refuses_small_files() -> None:
    assert not should_segment(total_bytes=999, accepts_ranges=True, count=4, min_bytes=1000)
    assert should_segment(total_bytes=1000, accepts_ranges=True, count=4, min_bytes=1000)


def test_a_single_segment_is_not_worth_the_machinery() -> None:
    assert not should_segment(total_bytes=100_000_000, accepts_ranges=True, count=1, min_bytes=1)
