from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from tests.engine.torrent.cli_support import Live


@pytest.fixture
def live(tmp_path: Path) -> Iterator:
    """`live(files, delay, **make_seeder_options)` starts a seeder and tracker; all are stopped after the test."""
    made: list[Live] = []

    def make(files: dict[str, bytes], delay: float = 0.0, **kw: Any) -> Live:
        lv = Live(tmp_path / f"seed{len(made)}", files, delay, **kw)
        made.append(lv)
        return lv

    yield make
    for lv in made:
        lv.stop()
