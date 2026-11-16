import io

from rich.console import Console
from src.core.progress import NoticeReporter, NullReporter, Progress, RichReporter


def test_null_reporter_accepts_everything() -> None:
    r = NullReporter()
    r.update(Progress(done=1, total=2, speed=0.0))
    r.notice("hi")


def test_rich_reporter_shows_notice_and_progress_on_its_console() -> None:
    buf = io.StringIO()
    r = RichReporter(Console(file=buf, force_terminal=True, width=100))
    r.update(Progress(done=512, total=1024, speed=100.0, name="a.bin", phase="downloading", peers=3))
    r.notice("starting over")
    r.close()
    out = buf.getvalue()
    assert "starting over" in out
    assert "a.bin" in out
    assert "50%" in out
    assert "downloading" in out
    assert "3 peers" in out


def test_rich_reporter_does_not_interpret_markup_in_names() -> None:
    buf = io.StringIO()
    r = RichReporter(Console(file=buf, force_terminal=False, width=100))
    r.update(Progress(done=1, total=2, speed=0.0, name="[red]x[/red].bin"))
    r.close()
    assert "[red]x[/red].bin" in buf.getvalue()


def test_notice_reporter_prints_notices_but_no_progress() -> None:
    buf = io.StringIO()
    r = NoticeReporter(Console(file=buf, width=100))
    r.update(Progress(done=1, total=2, speed=0.0, name="a.bin"))
    r.notice("resuming a.bin")
    assert buf.getvalue() == "resuming a.bin\n"
