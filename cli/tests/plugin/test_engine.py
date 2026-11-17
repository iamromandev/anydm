import os
from pathlib import Path
from typing import Any

import pytest
from src.core.errors import DownloadError, UsageError
from src.engine import Job
from src.plugin.engine import PluginEngine, _host_path
from src.plugin.model import Plugin
from src.plugin.runner import PluginRunner

from tests.conftest import FakeDocker
from tests.engine.test_media import Collect

HELLO = {"type": "hello", "protocol": 3}
URL = "https://example.org/a"
PLUGIN = Plugin("example", "example-image:1", "1", frozenset({"check", "download", "info"}), frozenset({"network"}))
NO_INFO = Plugin("quiet", "quiet-image:1", "1", frozenset({"check", "download"}), frozenset())


def job(dest: Path, **kw: Any) -> Job:
    base: dict[str, Any] = {"source": URL, "dest_dir": dest, "connections": 4, "format": None,
                            "playlist": False, "seed": False, "insecure": False}
    return Job(**{**base, **kw})


def engine(plugin: Plugin = PLUGIN) -> PluginEngine:
    return PluginEngine(PluginRunner(os.environ["ANYDM_DOCKER"], "anydm-media:0.0.1"), plugin)


def run_call(fake_docker: FakeDocker) -> list[str]:
    [call] = [c for c in fake_docker.calls() if c[0] == "run"]
    return call


async def test_a_plugin_download_runs_its_own_image_and_maps_paths(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}])
    paths = await engine().run(job(tmp_path), Collect())
    assert paths == [tmp_path.resolve() / "a.bin"]
    call = run_call(fake_docker)
    assert call[call.index("--") + 1:] == ["example-image:1", "download", "--protocol", "3", f"--url={URL}"]
    assert "anydm=plugin" in call and "anydm.plugin=example" in call
    assert call[call.index("--network") + 1] == "default"


async def test_a_plugin_without_the_network_permission_runs_with_none(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}])
    await engine(NO_INFO).run(job(tmp_path), Collect())
    assert run_call(fake_docker)[run_call(fake_docker).index("--network") + 1] == "none"


async def test_cookies_are_not_given_to_a_plugin_without_the_permission(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}])
    await engine().run(job(tmp_path, cookies=tmp_path / "c.txt"), Collect())
    assert "--mount" not in run_call(fake_docker)


async def test_done_without_paths_says_the_plugin_saved_nothing(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": []}])
    with pytest.raises(DownloadError, match="plugin example saved nothing"):
        await engine().run(job(tmp_path), Collect())


async def test_a_plugin_error_and_its_exit_name_the_plugin(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO], exit=7, stderr="oops")
    with pytest.raises(DownloadError, match=r"plugin example exited with 7: oops"):
        await engine().run(job(tmp_path), Collect())


async def test_a_plugin_that_speaks_another_protocol_is_named(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[{"type": "hello", "protocol": 9}])
    with pytest.raises(DownloadError, match=r"plugin example: it speaks a different protocol"):
        await engine().run(job(tmp_path), Collect())


async def test_text_from_the_plugin_is_cleaned_and_capped(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[
        HELLO,
        {"type": "progress", "done": 1, "total": 2, "speed": 0, "name": "a\x1b[2J‮b"},
        {"type": "notice", "message": "n\x07" + "x" * 2000},
        {"type": "done", "paths": ["/downloads/a.bin"]},
    ])
    report = Collect()
    await engine().run(job(tmp_path), report)
    assert report.updates[0].name == "a[2Jb"
    assert report.notices[0].startswith("nxxx") and len(report.notices[0]) == 500


async def test_too_many_lines_end_the_download(fake_docker: FakeDocker, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from src.plugin import contract

    monkeypatch.setattr(contract, "MAX_LINES", 5)
    fake_docker.set(lines=[HELLO, *[{"type": "notice", "message": "x"}] * 10, {"type": "done", "paths": ["/downloads/a"]}])
    with pytest.raises(DownloadError, match="too many lines"):
        await engine().run(job(tmp_path), Collect())


async def test_progress_lines_do_not_count_against_the_line_limit(fake_docker: FakeDocker, tmp_path: Path) -> None:
    progress = {"type": "progress", "done": 1, "total": 2, "speed": 0}
    fake_docker.set(lines=[HELLO, *[progress] * 20_000, {"type": "done", "paths": ["/downloads/a.bin"]}])
    assert await engine().run(job(tmp_path), Collect()) == [tmp_path.resolve() / "a.bin"]


async def test_cookies_flag_follows_the_permission(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}])
    c = tmp_path / "c.txt"
    await engine(NO_INFO).run(job(tmp_path, cookies=c), Collect())
    assert "--cookies" not in run_call(fake_docker)


async def test_cookies_flag_is_passed_with_the_permission(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}])
    with_cookies = Plugin("example", "example-image:1", "1", frozenset({"download"}), frozenset({"cookies"}))
    await engine(with_cookies).run(job(tmp_path, cookies=tmp_path / "c.txt"), Collect())
    assert run_call(fake_docker)[-1] == "--cookies"


@pytest.mark.parametrize("bad", ["/etc/passwd", "/downloads/../x", "relative", "/downloadsX/a", "/downloads", None, 3])
def test_a_path_outside_the_folder_is_refused(tmp_path: Path, bad: object) -> None:
    with pytest.raises(DownloadError):
        _host_path(tmp_path, bad)


def test_a_container_path_is_translated_to_the_host_folder(tmp_path: Path) -> None:
    assert _host_path(tmp_path, "/downloads/sub/a.bin") == tmp_path.resolve() / "sub" / "a.bin"


async def test_info_returns_the_parsed_info_line(fake_docker: FakeDocker) -> None:
    fake_docker.set(info_lines=[HELLO, {"type": "info", "title": "T", "size": 5, "formats": [], "items": []}])
    info = await engine().info(URL)
    assert (info.title, info.size) == ("T", 5)
    call = run_call(fake_docker)
    assert call[call.index("--") + 1:] == ["example-image:1", "info", "--protocol", "3", f"--url={URL}"]


async def test_info_error_line_is_a_download_error(fake_docker: FakeDocker) -> None:
    fake_docker.set(info_lines=[HELLO, {"type": "error", "message": "no such thing", "partial": []}], info_exit=1)
    with pytest.raises(DownloadError, match="no such thing"):
        await engine().info(URL)


async def test_info_without_an_info_line_fails(fake_docker: FakeDocker) -> None:
    fake_docker.set(info_lines=[HELLO])
    with pytest.raises(DownloadError, match="returned no information"):
        await engine().info(URL)


async def test_a_plugin_without_info_is_not_run(fake_docker: FakeDocker) -> None:
    with pytest.raises(UsageError, match="plugin quiet cannot show info"):
        await engine(NO_INFO).info(URL)
    assert fake_docker.calls() == []


async def test_a_plugin_without_download_is_not_run(fake_docker: FakeDocker, tmp_path: Path) -> None:
    lookup = Plugin("lookup", "lookup-image:1", "1", frozenset({"check", "info"}), frozenset())
    with pytest.raises(UsageError, match="plugin lookup cannot download") as e:
        await engine(lookup).run(job(tmp_path), Collect())
    assert e.value.exit_code == 2
    assert fake_docker.calls() == []


async def test_a_download_without_docker_names_the_plugin_not_media(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(version="fail")
    with pytest.raises(DownloadError, match=r"plugin example needs Docker.*anydm doctor") as e:
        await engine().run(job(tmp_path), Collect())
    assert "media" not in str(e.value)


async def test_a_media_download_without_docker_still_says_media(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(version="fail")
    with pytest.raises(DownloadError, match="media downloads need Docker; see anydm doctor"):
        await PluginEngine(PluginRunner(os.environ["ANYDM_DOCKER"], "anydm-media:0.0.1")).run(job(tmp_path), Collect())


async def test_cancelling_info_removes_its_container(fake_docker: FakeDocker) -> None:
    import asyncio

    fake_docker.set(info_lines=[HELLO], info_hang=True)
    task = asyncio.create_task(engine().info(URL))
    for _ in range(100):
        await asyncio.sleep(0.05)
        if any(c[0] == "run" for c in fake_docker.calls()):
            break
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    [name] = [c[c.index("--name") + 1] for c in fake_docker.calls() if c[0] == "run"]
    assert name in fake_docker.marked("removed")


PINNED = "ghcr.io/acme/p@sha256:" + "a" * 64
WRONG = "ghcr.io/acme/p@sha256:" + "b" * 64
PINNED_PLUGIN = Plugin("example", PINNED, "1", frozenset({"check", "download", "info"}), frozenset({"network"}))


async def test_a_download_runs_the_pinned_reference_and_pulls_a_missing_image_by_that_digest(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(plugins={PINNED: {
        "present": False, "pulled_inspect": f'["{PINNED}"] sha256:' + "c" * 64,
        "lines": [HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}],
    }})
    report = Collect()
    await engine(PINNED_PLUGIN).run(job(tmp_path), report)
    assert ["pull", "--", PINNED] in fake_docker.calls()
    assert any(PINNED in n and "pulling" in n for n in report.notices)
    assert fake_docker.commands().index("pull") < fake_docker.commands().index("run")
    assert run_call(fake_docker)[run_call(fake_docker).index("--") + 1] == PINNED


async def test_a_download_does_not_pull_an_image_that_is_here(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(plugins={PINNED: {"lines": [HELLO, {"type": "done", "paths": ["/downloads/a.bin"]}]}})
    await engine(PINNED_PLUGIN).run(job(tmp_path), Collect())
    assert "pull" not in fake_docker.commands()


async def test_a_pulled_image_with_another_digest_fails_the_download_before_it_runs(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(plugins={PINNED: {"present": False, "pulled_inspect": f'["{WRONG}"] sha256:' + "c" * 64}})
    with pytest.raises(DownloadError, match="does not match"):
        await engine(PINNED_PLUGIN).run(job(tmp_path), Collect())
    assert "run" not in fake_docker.commands()


async def test_info_pulls_a_missing_pinned_image_too(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={PINNED: {
        "present": False, "pulled_inspect": f'["{PINNED}"] sha256:' + "c" * 64,
        "info_lines": [HELLO, {"type": "info", "title": "T"}],
    }})
    notes: list[str] = []
    assert (await engine(PINNED_PLUGIN).info(URL, notice=notes.append)).title == "T"
    assert ["pull", "--", PINNED] in fake_docker.calls() and any("pulling" in n for n in notes)


async def test_a_pinned_image_that_is_gone_and_cannot_be_pulled_says_to_add_the_plugin_again(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    image_id = "sha256:" + "c" * 64
    gone = Plugin("example", image_id, "1", frozenset({"download"}), frozenset())
    fake_docker.set(plugins={image_id: {"present": False}})
    with pytest.raises(DownloadError, match=r"anydm plugin add"):
        await engine(gone).run(job(tmp_path), Collect())
