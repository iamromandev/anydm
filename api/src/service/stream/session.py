"""One ephemeral playback session and its per-segment state machine.

No two segments for the same index are ever generated concurrently:
``StreamService._ensure_segment`` checks a segment's state and flips it to
``GENERATING`` with no ``await`` in between, so a second caller for the same
index always observes the flip before it can start its own encode — it waits
on the segment's ``asyncio.Event`` instead. An encode that fails puts the
segment back to ``NOT_STARTED`` with a fresh event and wakes the waiters, which
check again and try for themselves. See that method for the mechanism; this
module only holds the data it operates on.
"""

from __future__ import annotations

import asyncio
import math
import time
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path

from src.lib.media.source import MediaInput


class SegmentState(Enum):
    NOT_STARTED = "not_started"
    GENERATING = "generating"
    READY = "ready"


@dataclass(frozen=True, slots=True)
class SiteOrigin:
    """The page a site session's inputs came from, to ask again when they expire."""

    page_url: str
    #: One per input, in the same order.
    format_ids: tuple[str, ...]


@dataclass
class StreamSession:
    id: str
    #: One source, or a site's separate video and audio; see ``segment_args``.
    inputs: list[MediaInput]
    duration_seconds: float
    has_video: bool
    segment_seconds: int
    session_dir: Path
    #: Lives on the session, not on ``StreamService``, because
    #: ``get_stream_service()`` builds a fresh service instance per request
    #: (matching ``get_download_service()``) — a semaphore stored on the
    #: service would be a different object on every request and enforce
    #: nothing. The session is held by the process-wide ``StreamSessionStore``
    #: singleton, so its semaphore is the one thing every request shares.
    encode_semaphore: asyncio.Semaphore
    states: dict[int, SegmentState] = field(default_factory=dict)
    events: dict[int, asyncio.Event] = field(default_factory=dict)
    background_tasks: list[asyncio.Task] = field(default_factory=list)
    #: The torrent swarm-progress poller. Kept separate from
    #: ``background_tasks`` because it deliberately outlives the probe
    #: task — it keeps publishing peer/speed/progress updates through
    #: playback, not just while connecting — and stops only when
    #: ``stop_session()`` cancels it.
    progress_task: asyncio.Task | None = None
    last_accessed: float = field(default_factory=time.monotonic)
    #: Set only for torrent-backed sessions. ``stop_session()`` uses this to
    #: decide whether it's safe to delete the underlying rqbit torrent.
    info_hash: str | None = None
    #: "connecting" while a torrent-backed session is waiting for real data
    #: and ffprobe; "ready" once playback can start; "error" if it never did.
    #: Direct-URL sessions are "ready" the moment they're created, since
    #: ``start_session`` already probes synchronously before returning one.
    status: str = "ready"
    error: str | None = None
    #: Set only for a page on a site, whose media URLs expire.
    origin: SiteOrigin | None = None
    #: Bumped each time ``inputs`` is resolved again. An encode notes it before
    #: it starts, so of the segments refused together, only the first to take
    #: ``refresh_lock`` asks the site again; the rest find it already done.
    inputs_version: int = 0
    refresh_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    @property
    def segment_count(self) -> int:
        return max(1, math.ceil(self.duration_seconds / self.segment_seconds))

    def segment_duration(self, index: int) -> float:
        start = index * self.segment_seconds
        return max(0.0, min(float(self.segment_seconds), self.duration_seconds - start))

    def segment_path(self, index: int) -> Path:
        return self.session_dir / f"segment_{index}.ts"

    def state_of(self, index: int) -> SegmentState:
        return self.states.get(index, SegmentState.NOT_STARTED)

    def event_for(self, index: int) -> asyncio.Event:
        if index not in self.events:
            self.events[index] = asyncio.Event()
        return self.events[index]

    def touch(self) -> None:
        self.last_accessed = time.monotonic()

    def idle_seconds(self, *, now: float | None = None) -> float:
        return (now if now is not None else time.monotonic()) - self.last_accessed


class StreamSessionStore:
    """Every live session, keyed by id. One instance for the whole process."""

    def __init__(self) -> None:
        self._sessions: dict[str, StreamSession] = {}

    def add(self, session: StreamSession) -> None:
        self._sessions[session.id] = session

    def get(self, session_id: str) -> StreamSession | None:
        return self._sessions.get(session_id)

    def remove(self, session_id: str) -> StreamSession | None:
        return self._sessions.pop(session_id, None)

    def all(self) -> list[StreamSession]:
        return list(self._sessions.values())
