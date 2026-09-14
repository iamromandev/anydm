from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Mapping, Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Reconciled:
    #: Segment index -> bytes already on disk, counted from the segment's start.
    watermarks: dict[int, int]
    #: True when the stored plan did not match and the rows were replaced. The
    #: caller must discard the ``.part`` file: nothing on disk describes the new
    #: ranges, and resuming into them would corrupt the result silently.
    fresh: bool


class SegmentRepo(ABC):
    @abstractmethod
    async def reconcile(self, task_id: uuid.UUID, part: str, plan: Sequence[tuple[int, int, int]]) -> Reconciled:
        """Match ``plan`` against the stored rows, replacing them if it differs.

        ``plan`` is ``(index, start, end)`` triples rather than the service
        layer's ``Segment`` — the data layer must not import from ``src.service``.
        """
        ...

    @abstractmethod
    async def flush(self, task_id: uuid.UUID, part: str, watermarks: Mapping[int, int]) -> None:
        """Write every watermark in one statement."""
        ...

    @abstractmethod
    async def progress(self, task_id: uuid.UUID, part: str) -> int:
        """Bytes already on disk for this part, across every stored segment.

        Read before planning: a part with progress keeps the plan that produced
        it, whatever the configured segment count now says. Re-planning it would
        discard bytes that are already downloaded.
        """
        ...

    @abstractmethod
    async def clear(self, task_id: uuid.UUID, part: str | None = None) -> None:
        """Delete one part's segments, or all of the task's."""
        ...
