import asyncio
import contextlib
import signal
from collections.abc import Callable
from pathlib import Path
from typing import ClassVar

from rich.text import Text
from textual import on
from textual.app import App, ComposeResult
from textual.binding import Binding, BindingType
from textual.containers import Vertical
from textual.screen import ModalScreen
from textual.widgets import DataTable, Footer, Input, Label, Static

from src.config.settings import Settings
from src.core.errors import AnydmError
from src.core.names import clean_display
from src.engine import Engine, Kind, engine_for, resolve
from src.engine.docker import in_thread
from src.engine.http.probe import safe_url
from src.tui import system
from src.tui.queue import Item, Queue, State

MIN_WIDTH, MIN_HEIGHT = 80, 24
NOTICE_WIDTH = 50
COLUMNS = ("name", "kind", "state", "size", "%", "speed", "ETA", "peers")


def _size(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return ""


def _eta(seconds: float) -> str:
    s = int(seconds)
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def _shown_source(source: str) -> str:
    try:
        return clean_display(safe_url(source))  # no user:pass
    except ValueError:
        return clean_display(source)


def render_row(item: Item) -> tuple[str, ...]:
    """The cells of one row, as plain text. Everything from outside goes through clean_display."""
    p = item.progress
    name = (p.name if p and p.name else None) or _shown_source(item.source)
    state = item.state.value
    if item.state is State.FAILED and item.error:
        state = f"failed: {item.error}"
    elif item.state is State.RUNNING and p and p.phase:
        state = f"running ({clean_display(p.phase)})"
    if item.notice and item.state is not State.FAILED:
        state += f" - {clean_display(item.notice)[:NOTICE_WIDTH]}"
    size = pct = speed = eta = peers = ""
    if p:
        size = _size(p.done) + (f" / {_size(p.total)}" if p.total else "")
        if p.total:
            pct = f"{min(100, p.done * 100 // p.total)}%"
            if p.speed > 0 and item.state is State.RUNNING:
                eta = _eta((p.total - p.done) / p.speed)
        if item.state is State.RUNNING and p.speed > 0:
            speed = f"{_size(p.speed)}/s"
        if p.peers is not None:
            peers = str(p.peers)
    return (name, item.kind.value, state, size, pct, speed, eta, peers)


class Table(DataTable[Text]):
    """The queue. Its arrow keys are listed in the footer."""

    BINDINGS: ClassVar[list[BindingType]] = [
        Binding("up", "cursor_up", "Up", key_display="↑"),
        Binding("down", "cursor_down", "Down", key_display="↓"),
    ]


class AddScreen(ModalScreen[tuple[str, Path] | None]):
    BINDINGS: ClassVar[list[BindingType]] = [Binding("escape", "dismiss(None)", "Cancel")]

    def __init__(self, default_dir: Path, add: Callable[[str, Path], str | None]) -> None:
        super().__init__()
        self._default_dir = default_dir
        self._add = add

    def compose(self) -> ComposeResult:
        with Vertical(id="add-box"):
            yield Label("Link (URL, magnet or .torrent file)")
            yield Input(id="link", placeholder="https://...")
            yield Label("Folder")
            yield Input(str(self._default_dir), id="folder")
            yield Label("", id="add-error", markup=False)

    @on(Input.Submitted)
    async def _submit(self) -> None:
        link = self.query_one("#link", Input).value.strip()
        folder = self.query_one("#folder", Input).value.strip()
        error = await in_thread(self._check, link)  # a daemon thread: a media check cannot hold up quitting
        if error is None:
            error = self._add(link, Path(folder or str(self._default_dir)).expanduser())
        if error is None:
            self.dismiss(None)
        else:
            self.query_one("#add-error", Label).update(error)

    @staticmethod
    def _check(link: str) -> str | None:
        if not link:
            return "enter a link"
        try:
            resolve(link, None)  # off the UI thread: a media check imports yt-dlp's extractors or starts a container
        except AnydmError as e:  # a usage error, or a media image that needs an update
            return clean_display(str(e))
        return None


class ConfirmQuit(ModalScreen[bool]):
    BINDINGS: ClassVar[list[BindingType]] = [
        Binding("y", "dismiss(True)", "Yes"),
        Binding("n,escape", "dismiss(False)", "No"),
    ]

    def compose(self) -> ComposeResult:
        with Vertical(id="confirm-box"):
            yield Label("Downloads are running. Stop them and quit? Partial data is kept.  [y]es / [n]o", markup=False)


class AnydmApp(App[None]):
    CSS = """
    AddScreen, ConfirmQuit { align: center middle; }
    #add-box, #confirm-box { width: 70; height: auto; border: round $accent; padding: 1 2; background: $surface; }
    #toosmall { display: none; width: 100%; height: 100%; content-align: center middle; }
    """
    BINDINGS: ClassVar[list[BindingType]] = [
        Binding("a", "add", "Add"),
        Binding("c", "cancel", "Cancel"),
        Binding("r", "retry", "Retry"),
        Binding("d", "remove", "Remove"),
        Binding("o", "open_folder", "Open folder"),
        Binding("y", "copy_path", "Copy path"),
        Binding("q", "quit_app", "Quit"),
        Binding("ctrl+c", "quit_now", "Quit", show=False, priority=True),
    ]

    def __init__(
        self,
        settings: Settings,
        max_active: int,
        engine_for: Callable[[Kind], Engine] = engine_for,
    ) -> None:
        super().__init__()
        self._settings = settings
        self._dirty = True
        self._queue = Queue(settings, max_active, self._mark_dirty, engine_for)
        self._drawn: dict[str, tuple[str, ...]] = {}
        self._quitting = False
        self._sigint_installed = False
        self._table_ready = False

    @property
    def queue(self) -> Queue:
        return self._queue

    def _mark_dirty(self) -> None:
        # Thread-safe: it may be called from yt-dlp's thread. It only assigns a bool (one atomic store); the UI
        # thread reads and clears it in _redraw, and a store that races the clear costs at most one extra redraw.
        self._dirty = True

    def compose(self) -> ComposeResult:
        yield Table(id="table", cursor_type="row")
        yield Static("terminal too small: need at least 80x24", id="toosmall")
        yield Footer()

    def on_mount(self) -> None:
        table = self.query_one(DataTable)
        for column in COLUMNS:
            table.add_column(column, key=column)
        table.focus()
        self._table_ready = True
        self.set_interval(0.25, self._redraw)
        self._check_size()
        with contextlib.suppress(NotImplementedError, RuntimeError):  # no signal handlers off the main thread
            asyncio.get_running_loop().add_signal_handler(signal.SIGINT, lambda: self.run_worker(self._quit()))
            self._sigint_installed = True

    async def on_unmount(self) -> None:
        try:
            await self._queue.shutdown()  # whatever way the app ends, no download task is left behind
        finally:
            if self._sigint_installed:
                self._sigint_installed = False
                with contextlib.suppress(NotImplementedError):  # platforms without signal handlers
                    asyncio.get_running_loop().remove_signal_handler(signal.SIGINT)

    def on_resize(self) -> None:
        if self._table_ready:  # a resize can arrive before compose has built the widgets
            self._check_size()

    def _check_size(self) -> None:
        small = self.size.width < MIN_WIDTH or self.size.height < MIN_HEIGHT
        self.query_one("#toosmall", Static).display = small
        self.query_one(DataTable).display = not small

    def _redraw(self) -> None:
        if not self._dirty:
            return
        self._dirty = False
        table = self.query_one(DataTable)
        ids = {str(i.id) for i in self._queue.items}
        for key in [k for k in self._drawn if k not in ids]:
            table.remove_row(key)
            del self._drawn[key]
        for item in self._queue.items:
            key = str(item.id)
            cells = render_row(item)
            if key not in self._drawn:
                table.add_row(*(Text(c) for c in cells), key=key)
            elif self._drawn[key] != cells:
                for column, cell in zip(COLUMNS, cells, strict=True):
                    table.update_cell(key, column, Text(cell))
            self._drawn[key] = cells

    def _selected(self) -> Item | None:
        table = self.query_one(DataTable)
        if table.row_count == 0:
            return None
        key = table.coordinate_to_cell_key(table.cursor_coordinate).row_key.value
        return next((i for i in self._queue.items if str(i.id) == key), None)

    def _act(self, name: str) -> None:
        item = self._selected()
        if item is not None:
            getattr(self._queue, name)(item.id)
            self._dirty = True
            self._redraw()

    def action_add(self) -> None:
        self.push_screen(AddScreen(self._settings.download_dir, self._add))

    def _add(self, link: str, dest: Path) -> str | None:
        try:
            self._queue.add(link, dest)
        except AnydmError as e:
            return clean_display(str(e))
        self._redraw()
        return None

    def action_cancel(self) -> None:
        self._act("cancel")

    def action_retry(self) -> None:
        self._act("retry")

    def action_remove(self) -> None:
        self._act("remove")

    def _done_item(self) -> Item | None:
        item = self._selected()
        return item if item is not None and item.state is State.DONE and item.paths else None

    def action_open_folder(self) -> None:
        item = self._done_item()
        if item is not None:
            system.open_folder(item.paths[0].parent, self)

    def action_copy_path(self) -> None:
        item = self._done_item()
        if item is not None:
            system.copy_path(item.paths[0], self)

    def action_quit_app(self) -> None:
        if self._queue.busy:
            self.push_screen(ConfirmQuit(), self._confirmed)
        else:
            self.exit()

    def _confirmed(self, yes: bool | None) -> None:
        if yes:
            self.run_worker(self._quit())

    def action_quit_now(self) -> None:
        self.run_worker(self._quit())

    async def _quit(self) -> None:
        if self._quitting:
            return
        self._quitting = True
        await self._queue.shutdown()
        self.exit()
