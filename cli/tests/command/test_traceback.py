import subprocess
import sys
from pathlib import Path

from src.main import app

CLI_DIR = Path(__file__).parents[2]


def test_the_app_never_shows_locals_in_a_traceback() -> None:
    assert app.pretty_exceptions_show_locals is False


def test_an_unexpected_crash_prints_a_traceback_without_the_variables_it_held() -> None:
    code = (
        "import sys, src.engine as e\n"
        "def boom(*a, **k):\n"
        "    raise RuntimeError('unexpected')\n"
        "e.resolve = boom\n"
        "sys.argv = ['anydm', 'get', 'http://tracker.example/x?passkey=SECRET', '--quiet']\n"
        "from src.main import main\n"
        "main()\n"
    )
    r = subprocess.run([sys.executable, "-c", code], cwd=CLI_DIR, capture_output=True, text=True, timeout=60)
    assert r.returncode != 0
    assert "RuntimeError" in r.stderr and "SECRET" not in r.stderr
