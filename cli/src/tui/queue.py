import asyncio
import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

from src.config.settings import Settings
from src.core.cookies import check_cookies
from src.core.errors import AnydmError, PartialDownload
from src.core.names import clean_display
from src.core.paths import writable_dir
from src.core.progress import Progress
from src.engine import Engine, Job, Kind, Route, engine_for, resolve, take_notices


class State(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    CANCELLING = "cancelling"
    DONE = "done"
    FAILED = "failed"
    CANCELLED = "cancelled"


_ACTIVE = (State.RUNNING, State.CANCELLING)


@dataclass
class Item:
    id: int
    source: str
    dest: Path
    route: Route
    state: State = State.QUEUED
    progress: Progress | None = None
    notice: str | None = None
    error: str | None = None
    paths: list[Path] = field(default_factory=list)
    run: int = 0  # which attempt is current; a retry starts a new one


class ItemReporter:
    """Stores the latest progress and notice of one run of an item. Safe to call from any thread (yt-dlp's
    worker). Once the item starts a new run, the reports of this one are ignored."""

    def __init__(self, item: Item, on_change: Callable[[], None]) -> None:
        self._item = item
        self._run = item.run
        self._on_change = on_change
        self._lock = threading.Lock()

    def update(self, p: Progress) -> None:
        with self._lock:
            if self._item.run != self._run:
                return
            name = clean_display(p.name) if p.name else None
            previous = self._item.progress
            if name is None and previous is not None:
                name = previous.name
            self._item.progress = Progress(p.done, p.total, p.speed, p.phase, p.peers, name)
        self._on_change()

    def notice(self, msg: str) -> None:
        with self._lock:
            if self._item.run != self._run:
                return
            self._item.notice = clean_display(msg)
        self._on_change()


class Queue:
    """Runs downloads on the running event loop, at most `max_active` at once, in the order they were added."""

    def __init__(
        self,
        settings: Settings,
        max_active: int,
        on_change: Callable[[], None],
        engine_for: Callable[[Route], Engine] = engine_for,
    ) -> None:
        self._settings = settings
        self._max_active = max_active
        self._on_change = on_change
        self._engine_for = engine_for
        self._items: list[Item] = []
        self._tasks: dict[int, asyncio.Task[list[Path]]] = {}
        self._next_id = 1
        self._deadline: float | None = None  # set by the first shutdown() and shared by every later one

    @property
    def items(self) -> list[Item]:
        return self._items

    @property
    def busy(self) -> bool:
        return any(i.state in (State.QUEUED, *_ACTIVE) for i in self._items)

    def add(self, source: str, dest: Path | None) -> Item:
        # Runs on the event loop, so it only reads what is known: the add dialog already resolved the link on a
        # worker thread (that is where a media check may start a container). Raises UsageError for a link
        # `anydm get` would reject.
        route = resolve(source, None, cached_only=True)
        item = Item(self._next_id, source, dest or self._settings.download_dir, route)
        notes = take_notices()  # e.g. media detection skipped because Docker is missing
        if notes:
            item.notice = clean_display(notes[0])
        self._next_id += 1
        self._items.append(item)
        self._pump()
        return item

    def cancel(self, item_id: int) -> None:
        item = self._find(item_id)
        if item is None:
            return
        if item.state is State.QUEUED:
            item.state = State.CANCELLED
        elif item.state is State.RUNNING:
            item.state = State.CANCELLING
            self._tasks[item.id].cancel()
        self._on_change()

    def retry(self, item_id: int) -> None:
        item = self._find(item_id)
        if item is None or item.state not in (State.FAILED, State.CANCELLED):
            return
        item.state = State.QUEUED
        item.error = None
        item.notice = None
        self._pump()

    def remove(self, item_id: int) -> None:
        item = self._find(item_id)
        if item is None or item.state in _ACTIVE:
            return
        self._items.remove(item)
        self._on_change()

    async def shutdown(self, timeout: float = 1.8) -> None:
        """Cancel everything and wait for the tasks. Every call shares the deadline of the first one, so
        calling it twice on the way out (quit, then unmount) never waits more than `timeout` in total.

        The default leaves room under the 2 s quit limit for a container's stop and removal (DockerMediaEngine
        finishes that within 1.5 s of the cancel), so the limit does not cut a cleanup off."""
        loop = asyncio.get_running_loop()
        if self._deadline is None:
            self._deadline = loop.time() + timeout
        for item in self._items:
            self.cancel(item.id)
        tasks = list(self._tasks.values())
        if tasks:
            await asyncio.wait(tasks, timeout=max(0.0, self._deadline - loop.time()))

    def _find(self, item_id: int) -> Item | None:
        return next((i for i in self._items if i.id == item_id), None)

    def _pump(self) -> None:
        running = sum(1 for i in self._items if i.state in _ACTIVE)
        for item in self._items:
            if running >= self._max_active:
                break
            if item.state is State.QUEUED:
                item.state = State.RUNNING
                item.run += 1
                task = asyncio.create_task(self._run(item))
                self._tasks[item.id] = task
                task.add_done_callback(lambda t, i=item: self._finished(i, t))
                running += 1
        self._on_change()

    async def _run(self, item: Item) -> list[Path]:
        dest = writable_dir(item.dest)  # at start, so an unwritable folder fails the row before any request
        report = ItemReporter(item, self._on_change)
        cookies: Path | None = None
        if item.route.kind is Kind.MEDIA and self._settings.cookies_file is not None:
            # only media reads them: an HTTP or torrent job never gets them, nor is it failed by a bad file
            checked = check_cookies(self._settings.cookies_file)  # a UsageError fails the row with its message
            cookies = checked.path
            if checked.warning:
                report.notice(checked.warning)
        job = Job(item.source, dest, self._settings.connections, None, False, False, False, cookies)
        return await self._engine_for(item.route).run(job, report)

    def _finished(self, item: Item, task: asyncio.Task[list[Path]]) -> None:
        if self._tasks.get(item.id) is task:
            del self._tasks[item.id]
        cancelling = item.state is State.CANCELLING  # whatever the engine did with the cancel, the user asked for it
        if task.cancelled():
            item.state = State.CANCELLED
        else:
            error = task.exception()
            if error is None:
                item.state = State.CANCELLED if cancelling else State.DONE
                item.paths = task.result()
            elif isinstance(error, PartialDownload):
                item.state = State.CANCELLED if cancelling else State.FAILED
                item.error = None if cancelling else clean_display(str(error))
                item.paths = error.paths
            elif isinstance(error, AnydmError):
                item.state = State.CANCELLED if cancelling else State.FAILED
                item.error = None if cancelling else clean_display(str(error))
            else:  # never show the message or traceback: it can carry credentials
                item.state = State.CANCELLED if cancelling else State.FAILED
                item.error = None if cancelling else f"internal error: {type(error).__name__}"
        self._pump()
