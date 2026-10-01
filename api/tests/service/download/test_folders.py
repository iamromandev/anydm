from pathlib import Path

import pytest
from src.core.error import Error
from src.service.download.folders import inside


def test_inside_resolves_under_the_root(tmp_path: Path) -> None:
    assert inside(tmp_path, "Video/Talks") == (tmp_path / "Video" / "Talks").resolve()


@pytest.mark.parametrize("escape", ["../elsewhere", "/etc", "Video/../../x"])
def test_inside_refuses_an_escape(tmp_path: Path, escape: str) -> None:
    with pytest.raises(Error):
        inside(tmp_path, escape)
