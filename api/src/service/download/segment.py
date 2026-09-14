"""Range plan arithmetic. Pure — no clock, no I/O, no HTTP.

Kept separate from the engine for the same reason ``progress.py`` is: an
off-by-one here writes a file that is the right size, passes every status check,
and is quietly corrupt. It is the part most worth testing on its own.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Segment:
    """A byte range, inclusive at both ends, the way HTTP means it."""

    index: int
    start: int
    end: int

    @property
    def length(self) -> int:
        return self.end - self.start + 1


def plan_segments(total_bytes: int, count: int) -> list[Segment]:
    """Split ``total_bytes`` into at most ``count`` gapless ranges.

    The remainder lands on the last segment rather than being spread: an even
    spread buys nothing measurable and makes the arithmetic harder to check by
    eye against a ``Content-Range`` header.
    """
    count = max(1, min(count, total_bytes))
    size = total_bytes // count
    segments: list[Segment] = []
    for index in range(count):
        start = index * size
        end = total_bytes - 1 if index == count - 1 else start + size - 1
        segments.append(Segment(index, start, end))
    return segments


def should_segment(*, total_bytes: int | None, accepts_ranges: bool, count: int, min_bytes: int) -> bool:
    """Whether splitting this transfer is worth it, and even possible."""
    if not accepts_ranges or count < 2 or total_bytes is None:
        return False
    return total_bytes >= min_bytes
