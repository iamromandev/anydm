import os
from pathlib import Path

import pytest
from src.core.errors import UsageError
from src.core.paths import writable_dir


def test_creates_missing_folder_and_returns_resolved_path(tmp_path: Path) -> None:
    target = tmp_path / "a" / "b"
    assert writable_dir(target) == target.resolve()
    assert target.is_dir()


@pytest.mark.skipif(os.geteuid() == 0, reason="root can write anywhere")
def test_unwritable_folder_is_a_usage_error(tmp_path: Path) -> None:
    ro = tmp_path / "ro"
    ro.mkdir(mode=0o500)
    with pytest.raises(UsageError, match="cannot"):
        writable_dir(ro / "sub")
    with pytest.raises(UsageError, match="cannot write"):
        writable_dir(ro)
