import threading
from dataclasses import dataclass
from typing import Any, Protocol

from rich.console import Console
from rich.progress import (
    BarColumn,
    DownloadColumn,
    Task,
    TaskID,
    TaskProgressColumn,
    TextColumn,
    TimeRemainingColumn,
    TransferSpeedColumn,
)
from rich.progress import (
    Progress as RichProgress,
)
from rich.text import Text


@dataclass
class Progress:
    done: int
    total: int | None
    speed: float
    phase: str | None = None
    peers: int | None = None
    name: str | None = None


class Reporter(Protocol):
    def update(self, p: Progress) -> None: ...

    def notice(self, msg: str) -> None: ...


class NullReporter:
    def update(self, p: Progress) -> None:
        pass

    def notice(self, msg: str) -> None:
        pass

    def close(self) -> None:
        pass


class _Extra(TextColumn):
    """Phase and peer count, when the task has them."""

    def __init__(self) -> None:
        super().__init__("")

    def render(self, task: Task) -> Text:
        parts = [str(task.fields["phase"])] if task.fields.get("phase") else []
        if task.fields.get("peers") is not None:
            parts.append(f"{task.fields['peers']} peers")
        return Text(" ".join(parts))


class RichReporter:
    """Progress bar and notices on a (stderr) console."""

    def __init__(self, console: Console | None = None) -> None:
        self._console = console or Console(stderr=True)
        self._progress = RichProgress(
            TextColumn("{task.fields[name]}", markup=False),
            BarColumn(),
            TaskProgressColumn(),
            DownloadColumn(),
            TransferSpeedColumn(),
            TimeRemainingColumn(),
            _Extra(),
            console=self._console,
            transient=False,
        )
        self._task: TaskID | None = None
        self._lock = threading.Lock()

    def update(self, p: Progress) -> None:
        fields: dict[str, Any] = {"name": p.name or "", "phase": p.phase, "peers": p.peers}
        with self._lock:
            if self._task is None:
                self._progress.start()
                self._task = self._progress.add_task("", total=p.total, completed=p.done, **fields)
            else:
                self._progress.update(self._task, total=p.total, completed=p.done, **fields)

    def notice(self, msg: str) -> None:
        self._console.print(msg, markup=False, highlight=False)

    def close(self) -> None:
        self._progress.stop()


class NoticeReporter(NullReporter):
    """No progress bar, but notices still go to the (stderr) console. For output that is not a terminal."""

    def __init__(self, console: Console | None = None) -> None:
        self._console = console or Console(stderr=True)

    def notice(self, msg: str) -> None:
        self._console.print(msg, markup=False, highlight=False)
