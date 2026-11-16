import io
import threading

from rich.console import Console
from src.core.progress import Progress, RichReporter


def test_concurrent_first_updates_create_one_task() -> None:
    rep = RichReporter(Console(file=io.StringIO()))
    barrier = threading.Barrier(8)

    def go() -> None:
        barrier.wait()
        rep.update(Progress(1, 10, 1.0, name="x"))

    threads = [threading.Thread(target=go) for _ in range(8)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    rep.close()
    assert len(rep._progress.tasks) == 1
