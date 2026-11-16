import email
import subprocess
from pathlib import Path

import pytest

CLI_DIR = Path(__file__).parents[1]


@pytest.fixture(scope="module")
def requires(tmp_path_factory: pytest.TempPathFactory) -> list[str]:
    out = tmp_path_factory.mktemp("wheel")
    subprocess.run(["uv", "build", "--wheel", "--out-dir", str(out)], cwd=CLI_DIR, check=True, capture_output=True)
    [wheel] = out.glob("*.whl")
    import zipfile

    with zipfile.ZipFile(wheel) as z:
        name = next(n for n in z.namelist() if n.endswith(".dist-info/METADATA"))
        meta = email.message_from_bytes(z.read(name))
    return meta.get_all("Requires-Dist") or []


def test_yt_dlp_is_not_a_required_dependency(requires: list[str]) -> None:
    plain = [r for r in requires if "extra ==" not in r]
    assert plain and not any("yt-dlp" in r for r in plain)


def test_media_extra_brings_yt_dlp_back(requires: list[str]) -> None:
    assert any(r.startswith("yt-dlp") and "extra == 'media'" in r.replace('"', "'") for r in requires)
