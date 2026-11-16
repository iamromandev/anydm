import os
from pathlib import Path

from src.core.errors import UsageError


def writable_dir(path: Path) -> Path:
    """Create `path` when missing and return it resolved; a UsageError when it cannot be written."""
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise UsageError(f"cannot use output folder {path}: {e.strerror}") from e
    if not os.access(path, os.W_OK | os.X_OK):
        raise UsageError(f"cannot write to output folder {path}")
    return path.resolve()
