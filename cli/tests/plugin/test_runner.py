import os
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.plugin import contract
from src.plugin.model import Plugin, media_image_plugin
from src.plugin.runner import COOKIES_MOUNT, PluginRunner

from tests.conftest import FakeDocker

IMAGE = "anydm-media:0.0.1"
URL = "https://example.org/a"


def plugin(*perms: str, name: str = "example") -> Plugin:
    return Plugin(name, "example-image:1", "1", frozenset({"check", "download", "info"}), frozenset(perms))


@pytest.fixture
def runner(fake_docker: FakeDocker) -> PluginRunner:
    return PluginRunner(os.environ["ANYDM_DOCKER"], IMAGE)


def mounts(args: list[str]) -> list[str]:
    return [args[i + 1] for i, a in enumerate(args) if a == "--mount"]


def network(args: list[str]) -> str:
    return args[args.index("--network") + 1]


@pytest.mark.parametrize("cmd", ["manifest", "check", "download", "info"])
def test_every_command_gets_the_sandbox_flags(runner: PluginRunner, tmp_path: Path, cmd: str) -> None:
    args = runner.plugin_args(plugin("network", "cookies"), cmd, tmp_path, None)
    assert args[:2] == ["run", "--rm"]
    assert args[args.index("--user") + 1] == f"{os.getuid()}:{os.getgid()}"
    assert args[args.index("--cap-drop") + 1] == "ALL"
    assert args[args.index("--security-opt") + 1] == "no-new-privileges"
    assert "--privileged" not in args
    assert args[-1] == "--"
    assert args[args.index("--name") + 1].startswith("anydm-example-")


def test_labels_name_the_plugin(runner: PluginRunner) -> None:
    args = runner.plugin_args(plugin(), "check", None, None)
    assert "anydm=plugin" in args and "anydm.plugin=example" in args
    assert "anydm=media" not in args


def test_the_builtin_keeps_the_media_label(runner: PluginRunner) -> None:
    args = runner.plugin_args(media_image_plugin(IMAGE), "check", None, None)
    assert "anydm=media" in args and "anydm.plugin=media" in args
    assert args[args.index("--name") + 1].startswith("anydm-media-")


@pytest.mark.parametrize("cmd", ["manifest", "check", "download", "info"])
def test_without_the_network_permission_the_network_is_off(runner: PluginRunner, cmd: str) -> None:
    assert network(runner.plugin_args(plugin(), cmd, None, None)) == "none"
    assert network(runner.plugin_args(plugin("cookies"), cmd, None, None)) == "none"
    assert network(runner.plugin_args(plugin("network"), cmd, None, None)) == ("none" if cmd in ("manifest", "check") else "default")


@pytest.mark.parametrize("cmd", ["manifest", "check"])
def test_manifest_and_check_never_get_the_network(runner: PluginRunner, cmd: str) -> None:
    assert network(runner.plugin_args(plugin("network", "cookies"), cmd, None, None)) == "none"


@pytest.mark.parametrize("cmd,mounted", [("manifest", False), ("check", False), ("download", True), ("info", False)])
def test_only_download_mounts_the_output_folder(runner: PluginRunner, tmp_path: Path, cmd: str, mounted: bool) -> None:
    args = runner.plugin_args(plugin("network"), cmd, tmp_path, None)
    assert ("-v" in args) is mounted
    if mounted:
        assert args[args.index("-v") + 1] == f"{tmp_path}:/downloads"


@pytest.mark.parametrize("cmd,mounted", [("manifest", False), ("check", False), ("download", True), ("info", True)])
def test_cookies_are_mounted_only_for_download_and_info(
    runner: PluginRunner, tmp_path: Path, cmd: str, mounted: bool
) -> None:
    cookies = tmp_path / "c.txt"
    args = runner.plugin_args(plugin("network", "cookies"), cmd, None, cookies)
    assert mounts(args) == ([f"type=bind,src={cookies},dst={COOKIES_MOUNT},readonly"] if mounted else [])


@pytest.mark.parametrize("cmd", ["download", "info"])
def test_cookies_need_the_cookies_permission(runner: PluginRunner, tmp_path: Path, cmd: str) -> None:
    assert mounts(runner.plugin_args(plugin("network"), cmd, None, tmp_path / "c.txt")) == []


def test_a_plugin_runs_in_a_container_not_as_root(
    runner: PluginRunner, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(os, "getuid", lambda: 0)
    with pytest.raises(DownloadError, match="root"):
        runner.plugin_args(plugin(), "check", None, None)


def test_reap_removes_a_dead_container_of_either_label(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(ps=["media1\t999999999\tx\tother\tmedia", "plug1\t999999999\tx\tother\tplugin",
                        "alien1\t999999999\tx\tother\tsomething-else"])
    runner.reap_stale()
    calls = fake_docker.calls()
    assert ["rm", "-f", "media1"] in calls and ["rm", "-f", "plug1"] in calls
    assert ["rm", "-f", "alien1"] not in calls


def test_reap_ignores_an_empty_label_value(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(ps=["empty1\t999999999\tx\tother\t"])
    runner.reap_stale()
    assert ["rm", "-f", "empty1"] not in fake_docker.calls()


def test_reap_asks_for_the_label_key_so_both_values_are_found(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    runner.reap_stale()
    [ps] = [c for c in fake_docker.calls() if c[0] == "ps"]
    assert ps[ps.index("--filter") + 1] == "label=anydm"
    assert '{{.Label "anydm"}}' in ps[ps.index("--format") + 1]


def test_digest_of_prefers_the_matching_repo_digest(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(inspect='["other/img@sha256:' + "a" * 64 + '","ghcr.io/x/p@sha256:' + "b" * 64 + '"] sha256:' + "c" * 64)
    assert runner.digest_of("ghcr.io/x/p:1") == "ghcr.io/x/p@sha256:" + "b" * 64


def test_digest_of_a_local_image_is_its_id(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(inspect="[] sha256:" + "c" * 64)
    assert runner.digest_of("local-plugin:1") == "sha256:" + "c" * 64


def test_digest_of_a_missing_image_is_none(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(image=False)
    assert runner.digest_of("nope:1") is None


def run_call(fake_docker: FakeDocker) -> list[str]:
    [c] = [c for c in fake_docker.calls() if c[0] == "run"]
    return c


def test_call_returns_the_lines_after_hello(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 3}, {"type": "notice", "message": "x"}, "junk"])
    assert runner.call(plugin("network"), "manifest", None, 5) == [{"type": "notice", "message": "x"}]
    call = run_call(fake_docker)
    assert call[call.index("--") + 1:] == ["example-image:1", "manifest", "--protocol", "3"]


def test_call_passes_the_url_as_one_option_and_cookies_as_a_flag(
    runner: PluginRunner, fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(info_lines=[{"type": "hello", "protocol": 3}])
    cookies = tmp_path / "c.txt"
    runner.call(plugin("network", "cookies"), "info", "-x; rm", 5, cookies=cookies)
    call = run_call(fake_docker)
    assert call[-5:] == ["info", "--protocol", "3", "--url=-x; rm", "--cookies"]
    assert mounts(call) == [f"type=bind,src={cookies},dst={COOKIES_MOUNT},readonly"]


def test_call_refuses_another_protocol(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 2}])
    with pytest.raises(DownloadError, match="protocol"):
        runner.call(plugin(), "manifest", None, 5)


def test_call_needs_hello_first(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[{"type": "notice", "message": "x"}])
    with pytest.raises(DownloadError, match="hello"):
        runner.call(plugin(), "manifest", None, 5)


def test_call_times_out_and_removes_the_container(
    runner: PluginRunner, fake_docker: FakeDocker
) -> None:
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 3}], manifest_hang=True)
    with pytest.raises(DownloadError, match="timed out"):
        runner.call(plugin(), "manifest", None, 0.4)
    [name] = [c[c.index("--name") + 1] for c in fake_docker.calls() if c[0] == "run"]
    assert name in fake_docker.marked("removed")


def test_call_reports_a_failed_container(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[], manifest_exit=3, manifest_stderr="boom")
    with pytest.raises(DownloadError, match=r"3.*boom"):
        runner.call(plugin(), "manifest", None, 5)


def test_call_bounds_what_it_reads(runner: PluginRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(contract, "MAX_LINES", 3)
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 3}, *[{"type": "notice", "message": "x"}] * 5])
    with pytest.raises(DownloadError, match="too many lines"):
        runner.call(plugin(), "manifest", None, 5)


def removed_after(fake_docker: FakeDocker) -> bool:
    [name] = [c[c.index("--name") + 1] for c in fake_docker.calls() if c[0] == "run"]
    return name in fake_docker.marked("removed")


def test_call_stops_and_removes_the_container_on_too_many_lines(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 3}, *[{"type": "notice", "message": "x"}] * 12_000],
                    manifest_hang=True)
    with pytest.raises(DownloadError, match="too many lines"):
        runner.call(plugin(), "manifest", None, 20)
    assert removed_after(fake_docker)


def test_call_skips_a_line_over_the_limit_without_reading_it_all(
    runner: PluginRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(contract, "MAX_LINE", 1000)
    monkeypatch.setattr("src.plugin.runner.MAX_LINE", 1000)
    fake_docker.set(manifest_lines=[{"type": "hello", "protocol": 3}, "x" * 5_000_000, {"type": "notice", "message": "y"}])
    assert runner.call(plugin(), "manifest", None, 20) == [{"type": "notice", "message": "y"}]


def test_call_keeps_only_the_tail_of_a_huge_stderr(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(manifest_lines=[], manifest_exit=3, manifest_stderr="a" * 3_000_000 + "END")
    with pytest.raises(DownloadError, match=r"END") as e:
        runner.call(plugin(), "manifest", None, 20)
    assert len(str(e.value)) < 2300


def test_call_removes_the_container_when_cancelled(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    import threading

    fake_docker.set(info_lines=[{"type": "hello", "protocol": 3}], info_hang=True)
    cancel = threading.Event()
    threading.Timer(0.5, cancel.set).start()
    with pytest.raises(DownloadError, match="cancel"):
        runner.call(plugin(), "info", URL, 20, cancel=cancel)
    assert removed_after(fake_docker)
