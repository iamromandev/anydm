import os
from pathlib import Path

from src.core.errors import DownloadError, UsageError


def writable_dir(path: Path) -> Path:
    """Create `path` when missing and return it resolved; a UsageError when it cannot be written."""
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise UsageError(f"cannot use output folder {path}: {e.strerror}") from e
    if not os.access(path, os.W_OK | os.X_OK):
        raise UsageError(f"cannot write to output folder {path}")
    return path.resolve()


def inside(dest: Path, raw: str) -> Path:
    """`raw` (absolute, or relative to `dest`) resolved under `dest`; a DownloadError when it lands outside."""
    base = dest.resolve()
    path = Path(raw)
    resolved = (path if path.is_absolute() else base / path).resolve()
    if not resolved.is_relative_to(base):
        raise DownloadError(f"refusing to use {raw!r}: it is outside the output folder")
    return base / resolved.relative_to(base)
