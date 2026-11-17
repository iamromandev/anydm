import os

import pytest
from src.plugin import route
from src.plugin.model import Plugin
from src.plugin.runner import PluginRunner

from tests.conftest import FakeDocker

URL = "https://example.org/a"
OTHER_URL = "https://example.org/b"
HELLO = {"type": "hello", "protocol": 3}
A_IMAGE = "ghcr.io/acme/a@sha256:" + "a" * 64
B_IMAGE = "ghcr.io/acme/b@sha256:" + "b" * 64


def plugin(name: str, image: str, *caps: str, perms: tuple[str, ...] = ("network", "cookies")) -> Plugin:
    return Plugin(name, image, "1", frozenset(caps or ("check", "download", "info")), frozenset(perms))


A, B = plugin("alpha", A_IMAGE), plugin("beta", B_IMAGE)


@pytest.fixture
def runner(fake_docker: FakeDocker) -> PluginRunner:
    return PluginRunner(os.environ["ANYDM_DOCKER"], "anydm-media:0.0.1")


def checks(fake: FakeDocker) -> list[list[str]]:
    return [c for c in fake.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]]


def asked_images(fake: FakeDocker) -> list[str]:
    return [c[c.index("--") + 1] for c in checks(fake)]


def pick(runner: PluginRunner, plugins: list[Plugin], url: str = URL, **kw: object) -> tuple[str | None, list[str]]:
    notes: list[str] = []
    return route.pick(url, plugins, runner, notes.append, **kw), notes  # ty: ignore[invalid-argument-type]


def test_the_first_plugin_that_says_yes_wins_with_one_check_each(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": True}})
    assert pick(runner, [A, B])[0] == "beta"
    assert asked_images(fake_docker) == [A_IMAGE, B_IMAGE]


def test_a_plugin_after_the_winner_is_not_asked(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": True}, B_IMAGE: {"check": True}})
    assert pick(runner, [A, B])[0] == "alpha"
    assert asked_images(fake_docker) == [A_IMAGE]


def test_nobody_saying_yes_is_none(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": False}})
    assert pick(runner, [A, B]) == (None, [])


def test_a_second_lookup_of_the_same_url_makes_no_call(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": True}})
    pick(runner, [A, B])
    before = len(fake_docker.calls())
    assert pick(runner, [A, B])[0] == "beta"
    assert len(fake_docker.calls()) == before


def test_the_cache_is_per_plugin_and_url(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": {URL: True}}, B_IMAGE: {"check": {OTHER_URL: True}}})
    assert pick(runner, [A, B], URL)[0] == "alpha"
    assert pick(runner, [A, B], OTHER_URL)[0] == "beta"
    assert asked_images(fake_docker) == [A_IMAGE, A_IMAGE, B_IMAGE]


def test_a_plugin_without_check_is_never_asked(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    quiet = plugin("quiet", A_IMAGE, "download", "info")
    fake_docker.set(plugins={A_IMAGE: {"check": True}, B_IMAGE: {"check": True}})
    assert pick(runner, [quiet, B])[0] == "beta"
    assert asked_images(fake_docker) == [B_IMAGE]


def test_check_runs_without_network_mounts_or_cookies(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    pick(runner, [A])
    [call] = checks(fake_docker)
    assert call[call.index("--network") + 1] == "none"
    assert "-v" not in call and "--mount" not in call and "--cookies" not in call
    assert call[call.index("--") + 1:] == [A_IMAGE, "check", "--protocol", "3", f"--url={URL}"]


def test_a_check_that_times_out_is_skipped_with_one_notice(
    runner: PluginRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(route, "CHECK_TIMEOUT", 0.4)
    fake_docker.set(plugins={A_IMAGE: {"check_lines": [HELLO], "check_hang": True}, B_IMAGE: {"check": True}})
    found, notes = pick(runner, [A, B])
    assert found == "beta"
    [note] = notes
    assert "alpha" in note and "timed out" in note
    [name] = [c[c.index("--name") + 1] for c in checks(fake_docker) if A_IMAGE in c]
    assert name in fake_docker.marked("removed")


def test_a_timed_out_check_is_not_remembered_so_the_plugin_is_asked_again(
    runner: PluginRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(route, "CHECK_TIMEOUT", 0.3)
    fake_docker.set(plugins={A_IMAGE: {"check_lines": [HELLO], "check_hang": True}})
    pick(runner, [A])
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    assert pick(runner, [A])[0] == "alpha"
    assert asked_images(fake_docker) == [A_IMAGE, A_IMAGE]


@pytest.mark.parametrize("failure", [
    {"check_lines": [], "check_exit": 1},
    {"check_lines": [HELLO]},
    {"check_lines": [HELLO, {"type": "error", "message": "busy", "partial": []}], "check_exit": 1},
])
def test_a_failed_check_is_not_remembered_as_a_no(
    runner: PluginRunner, fake_docker: FakeDocker, failure: dict
) -> None:
    fake_docker.set(plugins={A_IMAGE: failure})
    assert pick(runner, [A])[0] is None
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    assert pick(runner, [A])[0] == "alpha"


def test_a_real_no_is_remembered(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": False}})
    pick(runner, [A])
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    assert pick(runner, [A])[0] is None
    assert asked_images(fake_docker) == [A_IMAGE]


def test_a_plugin_without_download_is_not_asked_when_a_download_is_needed(
    runner: PluginRunner, fake_docker: FakeDocker
) -> None:
    lookup = plugin("lookup", A_IMAGE, "check", "info")
    fake_docker.set(plugins={A_IMAGE: {"check": True}, B_IMAGE: {"check": True}})
    assert pick(runner, [lookup, B])[0] == "beta"
    assert asked_images(fake_docker) == [B_IMAGE]


def test_a_plugin_without_info_is_not_asked_when_info_is_needed(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    quiet = plugin("quiet", A_IMAGE, "check", "download")
    fake_docker.set(plugins={A_IMAGE: {"check": True}, B_IMAGE: {"check": True}})
    assert pick(runner, [quiet, B], need="info")[0] == "beta"
    assert asked_images(fake_docker) == [B_IMAGE]
    assert pick(runner, [quiet], URL, need="download")[0] == "quiet"


def test_check_is_offline_even_for_a_plugin_with_the_network_permission(
    runner: PluginRunner, fake_docker: FakeDocker
) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    pick(runner, [plugin("alpha", A_IMAGE, perms=("network", "cookies"))])
    [call] = checks(fake_docker)
    assert call[call.index("--network") + 1] == "none"


def test_a_failing_check_is_skipped_with_a_notice(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check_lines": [], "check_exit": 1, "check_stderr": "boom"},
                             B_IMAGE: {"check": True}})
    found, notes = pick(runner, [A, B])
    assert found == "beta"
    [note] = notes
    assert "alpha" in note and "boom" in note


@pytest.mark.parametrize("lines", [
    [HELLO],
    [HELLO, {"type": "supported", "value": "yes"}],
    [HELLO, {"type": "error", "message": "no way", "partial": []}],
    [{"type": "hello", "protocol": 2}],
])
def test_a_check_without_a_clear_answer_is_skipped(runner: PluginRunner, fake_docker: FakeDocker, lines: list) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check_lines": lines}, B_IMAGE: {"check": True}})
    found, notes = pick(runner, [A, B])
    assert found == "beta" and len(notes) == 1


def test_a_missing_image_is_pulled_by_its_digest_before_the_check(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"present": False, "check": True,
                                       "pulled_inspect": f'["{A_IMAGE}"] sha256:' + "c" * 64}})
    found, notes = pick(runner, [A])
    assert found == "alpha"
    assert any(A_IMAGE in n and "pulling" in n for n in notes)
    commands = fake_docker.commands()
    assert commands.index("pull") < commands.index("run")
    assert ["pull", "--", A_IMAGE] in fake_docker.calls()


def test_an_image_that_cannot_be_pulled_skips_the_plugin(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"present": False, "pull": "fail"}, B_IMAGE: {"check": True}})
    found, notes = pick(runner, [A, B])
    assert found == "beta"
    assert any("cannot pull" in n for n in notes)
    assert asked_images(fake_docker) == [B_IMAGE]


def test_a_pulled_image_with_the_wrong_digest_skips_the_plugin(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"present": False, "pulled_inspect": f'["{B_IMAGE}"] sha256:' + "c" * 64}})
    found, notes = pick(runner, [A])
    assert found is None and any("does not match" in n for n in notes)
    assert asked_images(fake_docker) == []


def test_without_docker_there_is_one_notice_and_no_plugin(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    found, notes = pick(runner, [A, B])
    assert found is None
    [note] = notes
    assert "Docker" in note and "anydm doctor" in note and "skipped" in note
    assert asked_images(fake_docker) == []


def test_cached_only_reads_the_cache_and_never_runs_docker(runner: PluginRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": True}})
    assert pick(runner, [A, B], cached_only=True) == (None, [])
    assert fake_docker.calls() == []
    assert pick(runner, [A, B])[0] == "beta"
    calls = len(fake_docker.calls())
    assert pick(runner, [A, B], cached_only=True)[0] == "beta"
    assert len(fake_docker.calls()) == calls


def test_cached_only_treats_a_failed_check_as_no(
    runner: PluginRunner, fake_docker: FakeDocker
) -> None:
    fake_docker.set(plugins={A_IMAGE: {"check_lines": [], "check_exit": 1}})
    pick(runner, [A])
    calls = len(fake_docker.calls())
    assert pick(runner, [A], cached_only=True) == (None, [])
    assert len(fake_docker.calls()) == calls
