import threading
from pathlib import Path
from typing import Any

import pytest
from src.tui import system


class FakeApp:
    def __init__(self) -> None:
        self.notes: list[tuple[str, dict[str, Any]]] = []

    def notify(self, message: str, **kw: Any) -> None:
        self.notes.append((message, kw))


def test_missing_opener_notifies_an_error_with_the_folder(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    def boom(*a: Any, **kw: Any) -> None:
        raise FileNotFoundError(2, "No such file or directory", "xdg-open")

    monkeypatch.setattr(system.subprocess, "Popen", boom)
    app = FakeApp()
    system.open_folder(tmp_path, app)  # ty: ignore[invalid-argument-type]
    [(message, kw)] = app.notes
    assert kw["severity"] == "error"
    assert str(tmp_path) in message
    assert kw.get("markup") is False


def test_opener_runs_in_its_own_session_and_is_reaped(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    seen: dict[str, Any] = {}
    reaped = threading.Event()

    class FakePopen:
        def __init__(self, args: list[str], **kw: Any) -> None:
            seen["args"], seen["kw"] = args, kw

        def wait(self) -> int:
            reaped.set()
            return 0

    monkeypatch.setattr(system.subprocess, "Popen", FakePopen)
    app = FakeApp()
    system.open_folder(tmp_path, app)  # ty: ignore[invalid-argument-type]
    assert reaped.wait(2)
    assert seen["args"][1:] == [str(tmp_path)]
    assert seen["kw"]["start_new_session"] is True
    assert app.notes == []
