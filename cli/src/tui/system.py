import subprocess
import sys
import threading
from pathlib import Path

from textual.app import App


def open_folder(path: Path, app: App) -> None:
    """Open `path` in the system file manager. No shell: the path is one argument. A missing opener is shown, not raised."""
    opener = "open" if sys.platform == "darwin" else "xdg-open"
    try:
        proc = subprocess.Popen(
            [opener, str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except OSError as e:
        app.notify(f"cannot open {path}: {e.strerror or e}", title="Open folder", severity="error", markup=False)
        return
    threading.Thread(target=proc.wait, daemon=True, name="anydm-open").start()  # reap it: no zombie


def copy_path(path: Path, app: App) -> None:
    """Copy the absolute path through the terminal (OSC 52). There is no way to know it worked, so the path is
    always shown too."""
    absolute = str(path.resolve())
    app.copy_to_clipboard(absolute)
    app.notify(absolute, title="Path copied", markup=False)
