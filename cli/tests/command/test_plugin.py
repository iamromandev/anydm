import json
import stat
from pathlib import Path
from typing import Any

import pytest
from src.command import plugin as plugin_command
from src.main import app
from src.plugin import state
from src.plugin.model import Plugin
from typer.testing import CliRunner

from tests.conftest import FakeDocker

runner = CliRunner()
IMAGE = "ghcr.io/acme/anydm-pixiv:1.2"
DIGEST = "ghcr.io/acme/anydm-pixiv@sha256:" + "a" * 64
IMAGE_ID = "sha256:" + "c" * 64
MANIFEST: dict[str, Any] = {
    "name": "pixiv", "version": "1.2.0", "capabilities": ["check", "download", "info"],
    "permissions": ["network", "cookies"], "description": "Pixiv artworks",
}


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))


@pytest.fixture
def terminal(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(plugin_command, "_interactive", lambda: True)


def serve(fake: FakeDocker, manifest: dict[str, Any] | None = None, **extra: Any) -> None:
    """The fake docker holds `IMAGE` (pinned to DIGEST) as a plugin with `manifest`."""
    entry: dict[str, Any] = {"manifest": MANIFEST if manifest is None else manifest, **extra}
    fake.set(plugins={
        IMAGE: {**entry, "inspect": f'["{DIGEST}"] {IMAGE_ID}'},
        DIGEST: entry,
    })


def add(*args: str, input: str | None = None):
    return runner.invoke(app, ["plugin", "add", *args], input=input)


def installed() -> list[Plugin]:
    return state.load()


def manifest_call(fake: FakeDocker) -> list[str]:
    [call] = [c for c in fake.calls() if c[0] == "run"]
    return call


def test_add_with_yes_shows_the_plugin_and_saves_it_pinned_by_digest(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    result = add(IMAGE, "--yes")
    assert result.exit_code == 0, result.stderr
    assert "pixiv 1.2.0 — capabilities: check, download, info — asks for: network, cookies" in result.stdout
    assert "Pixiv artworks" in result.stdout
    [saved] = installed()
    assert saved == Plugin("pixiv", DIGEST, "1.2.0", frozenset({"check", "download", "info"}),
                           frozenset({"network", "cookies"}))
    assert stat.S_IMODE(state.state_path().stat().st_mode) == 0o600


def test_the_manifest_runs_on_the_pinned_image_with_no_network_and_no_mounts(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    add(IMAGE, "--yes")
    call = manifest_call(fake_docker)
    assert call[call.index("--") + 1:] == [DIGEST, "manifest", "--protocol", "3"]
    assert call[call.index("--network") + 1] == "none"
    assert "-v" not in call and "--mount" not in call and "--cookies" not in call


def test_a_plugin_that_asks_for_nothing_says_so(fake_docker: FakeDocker) -> None:
    serve(fake_docker, {**MANIFEST, "permissions": []})
    result = add(IMAGE, "--yes")
    assert "asks for: nothing" in result.stdout
    assert installed()[0].permissions == frozenset()


def test_a_local_only_image_is_pinned_by_its_image_id(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={"local-pixiv:dev": {"manifest": MANIFEST, "inspect": f"[] {IMAGE_ID}"},
                             IMAGE_ID: {"manifest": MANIFEST}})
    assert add("local-pixiv:dev", "--yes").exit_code == 0
    assert installed()[0].image == IMAGE_ID
    assert manifest_call(fake_docker)[-4] == IMAGE_ID


def test_without_yes_it_asks_and_y_installs(fake_docker: FakeDocker, terminal: None) -> None:
    serve(fake_docker)
    result = add(IMAGE, input="y\n")
    assert result.exit_code == 0, result.stderr
    assert "Allow?" in result.stdout
    assert [p.name for p in installed()] == ["pixiv"]


@pytest.mark.parametrize("answer", ["n\n", "\n", ""])
def test_anything_but_yes_installs_nothing(fake_docker: FakeDocker, terminal: None, answer: str) -> None:
    serve(fake_docker)
    result = add(IMAGE, input=answer)
    assert result.exit_code == 1
    assert installed() == []
    assert not state.state_path().exists()


def test_without_a_terminal_and_without_yes_it_exits_2_before_doing_anything(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    result = add(IMAGE)
    assert result.exit_code == 2
    assert "--yes" in result.stderr
    assert fake_docker.calls() == []
    assert not state.state_path().exists()


@pytest.mark.parametrize("manifest,why", [
    ({**MANIFEST, "name": "Bad_Name"}, "name"),
    ({**MANIFEST, "name": "x"}, "name"),
    ({**MANIFEST, "name": ""}, "name"),
    ({**MANIFEST, "name": "media"}, "reserved"),
    ({**MANIFEST, "name": "http"}, "reserved"),
    ({**MANIFEST, "name": "torrent"}, "reserved"),
    ({**MANIFEST, "capabilities": ["check", "fly"]}, "capabilities"),
    ({**MANIFEST, "capabilities": []}, "capabilities"),
    ({**MANIFEST, "permissions": ["network", "root"]}, "permissions"),
    ({**MANIFEST, "permissions": "network"}, "permissions"),
])
def test_an_invalid_manifest_exits_2_and_writes_nothing(fake_docker: FakeDocker, manifest: dict, why: str) -> None:
    serve(fake_docker, manifest)
    result = add(IMAGE, "--yes")
    assert result.exit_code == 2
    assert why in result.stderr
    assert not state.state_path().exists()


def test_a_manifest_with_hostile_text_is_cleaned(fake_docker: FakeDocker) -> None:
    serve(fake_docker, {**MANIFEST, "description": "ok\x1b[31m‮evil" + "x" * 900})
    result = add(IMAGE, "--yes")
    assert "\x1b" not in result.stdout and "‮" not in result.stdout
    assert "x" * 501 not in result.stdout


def test_a_plugin_that_prints_no_manifest_exits_1(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={IMAGE: {"manifest_lines": [{"type": "hello", "protocol": 3}], "inspect": f'["{DIGEST}"] x'},
                             DIGEST: {"manifest_lines": [{"type": "hello", "protocol": 3}]}})
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1 and "manifest" in result.stderr
    assert not state.state_path().exists()


def test_a_plugin_that_speaks_another_protocol_exits_1(fake_docker: FakeDocker) -> None:
    lines = [{"type": "hello", "protocol": 2}]
    fake_docker.set(plugins={IMAGE: {"manifest_lines": lines, "inspect": f'["{DIGEST}"] x'}, DIGEST: {"manifest_lines": lines}})
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1 and "protocol" in result.stderr


def test_a_manifest_that_hangs_fails_after_the_timeout_and_removes_the_container(
    fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(plugin_command, "MANIFEST_TIMEOUT", 0.4)
    serve(fake_docker, manifest_lines=[{"type": "hello", "protocol": 3}], manifest_hang=True)
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1 and "timed out" in result.stderr
    assert manifest_call(fake_docker)[manifest_call(fake_docker).index("--name") + 1] in fake_docker.marked("removed")
    assert not state.state_path().exists()


def test_the_manifest_timeout_is_30_seconds() -> None:
    assert plugin_command.MANIFEST_TIMEOUT == 30.0


def test_a_missing_image_is_pulled_first_with_a_notice(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={
        IMAGE: {"present": False, "manifest": MANIFEST, "pulled_inspect": f'["{DIGEST}"] {IMAGE_ID}'},
        DIGEST: {"manifest": MANIFEST},
    })
    result = add(IMAGE, "--yes")
    assert result.exit_code == 0, result.stderr
    assert ["pull", "--", IMAGE] in fake_docker.calls()
    assert f"pulling {IMAGE}" in result.stderr
    assert fake_docker.commands().index("pull") < fake_docker.commands().index("run")
    assert installed()[0].image == DIGEST


def test_a_present_image_is_not_pulled(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    add(IMAGE, "--yes")
    assert "pull" not in fake_docker.commands()


def test_a_failed_pull_exits_1_naming_the_reference(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={IMAGE: {"present": False, "pull": "fail"}})
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1
    assert IMAGE in result.stderr and "pull access denied" in result.stderr


def test_a_missing_local_image_is_not_pulled(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={"mine:dev": {"present": False}})
    result = add("mine:dev", "--yes")
    assert result.exit_code == 1 and "mine:dev" in result.stderr
    assert "pull" not in fake_docker.commands()


@pytest.mark.parametrize("ref", ["-rm", "--help", "a b", "x;y", ""])
def test_an_invalid_image_reference_exits_2_without_touching_docker(fake_docker: FakeDocker, ref: str) -> None:
    result = add(ref, "--yes") if ref != "--help" else add("--", ref, "--yes")
    assert result.exit_code == 2
    assert fake_docker.calls() == []


def test_without_docker_it_exits_1_naming_docker(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    fake_docker.set(version="fail")
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1
    assert "Docker" in result.stderr and "anydm doctor" in result.stderr


def test_a_corrupt_state_file_exits_2_naming_it(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    state.state_path().parent.mkdir(parents=True)
    state.state_path().write_text("broken [")
    result = add(IMAGE, "--yes")
    assert result.exit_code == 2 and str(state.state_path()) in result.stderr


def seed(*names: str, permissions: frozenset[str] = frozenset({"network"}), image: str | None = None) -> None:
    state.save([Plugin(n, image or f"example/{n}@sha256:" + "d" * 64, "0.1", frozenset({"check", "download"}), permissions)
                for n in names])


def test_adding_a_new_plugin_puts_it_before_media_after_the_others(fake_docker: FakeDocker) -> None:
    seed("zeta", "alpha")
    serve(fake_docker)
    add(IMAGE, "--yes")
    assert [p.name for p in installed()] == ["zeta", "alpha", "pixiv"]


def test_adding_again_with_an_extra_permission_shows_the_change_and_asks_again(
    fake_docker: FakeDocker, terminal: None
) -> None:
    seed("pixiv", "other", permissions=frozenset({"network"}), image="old/pixiv@sha256:" + "d" * 64)
    serve(fake_docker)
    result = add(IMAGE, input="n\n")
    assert result.exit_code == 1
    assert "already installed" in result.stdout
    assert "now asks for: cookies" in result.stdout
    assert "Allow?" in result.stdout
    assert installed()[0].image == "old/pixiv@sha256:" + "d" * 64  # nothing changed
    result = add(IMAGE, input="y\n")
    assert result.exit_code == 0
    [first, second] = installed()
    assert (first.name, first.image, first.permissions) == ("pixiv", DIGEST, frozenset({"network", "cookies"}))
    assert second.name == "other"  # the position is kept


def test_adding_again_shows_a_dropped_permission(fake_docker: FakeDocker, terminal: None) -> None:
    seed("pixiv", permissions=frozenset({"network", "cookies"}))
    serve(fake_docker, {**MANIFEST, "permissions": ["network"]})
    result = add(IMAGE, input="y\n")
    assert "no longer asks for: cookies" in result.stdout


def test_adding_again_without_a_change_says_so_and_still_asks(fake_docker: FakeDocker, terminal: None) -> None:
    seed("pixiv", permissions=frozenset({"network", "cookies"}))
    serve(fake_docker)
    result = add(IMAGE, input="n\n")
    assert "same permissions" in result.stdout and "Allow?" in result.stdout


def test_adding_again_with_yes_replaces_it_without_a_terminal_when_no_permission_is_added(
    fake_docker: FakeDocker,
) -> None:
    seed("pixiv", permissions=frozenset({"network", "cookies"}), image="old/pixiv@sha256:" + "d" * 64)
    serve(fake_docker)
    assert add(IMAGE, "--yes").exit_code == 0
    assert installed()[0].image == DIGEST
    assert add(IMAGE).exit_code == 2  # no terminal, no --yes


def test_adding_again_with_yes_may_drop_a_permission(fake_docker: FakeDocker) -> None:
    seed("pixiv", permissions=frozenset({"network", "cookies"}))
    serve(fake_docker, {**MANIFEST, "permissions": ["network"]})
    assert add(IMAGE, "--yes").exit_code == 0
    assert installed()[0].permissions == frozenset({"network"})


def test_yes_does_not_approve_a_permission_the_plugin_did_not_have(fake_docker: FakeDocker) -> None:
    seed("pixiv", permissions=frozenset({"network"}), image="old/pixiv@sha256:" + "d" * 64)
    serve(fake_docker)  # now asks for network and cookies
    before = state.state_path().read_text()
    result = add(IMAGE, "--yes")
    assert result.exit_code == 2
    assert "cookies" in result.stderr and "without --yes" in result.stderr
    assert "network" not in result.stderr.replace("now asks", "")  # only the new permission is named
    assert state.state_path().read_text() == before


def test_a_first_install_with_yes_approves_every_permission_it_asks_for(fake_docker: FakeDocker) -> None:
    serve(fake_docker)
    assert add(IMAGE, "--yes").exit_code == 0
    assert installed()[0].permissions == frozenset({"network", "cookies"})


def test_an_interactive_run_may_approve_an_added_permission(fake_docker: FakeDocker, terminal: None) -> None:
    seed("pixiv", permissions=frozenset({"network"}))
    serve(fake_docker)
    assert add(IMAGE, input="y\n").exit_code == 0
    assert installed()[0].permissions == frozenset({"network", "cookies"})


def test_adding_again_without_a_terminal_and_without_yes_changes_nothing(fake_docker: FakeDocker) -> None:
    seed("pixiv", permissions=frozenset({"network"}))
    serve(fake_docker)
    before = state.state_path().read_text()
    assert add(IMAGE).exit_code == 2
    assert state.state_path().read_text() == before


# -- text and saving that fail ------------------------------------------------------------------------------------

def test_a_lone_surrogate_in_the_manifest_is_dropped_and_the_plugin_installs(fake_docker: FakeDocker) -> None:
    serve(fake_docker, {**MANIFEST, "version": "1.2\ud800.0", "description": "Pix\udfffiv"})
    result = add(IMAGE, "--yes")
    assert result.exit_code == 0, result.stderr
    assert installed()[0].version == "1.2.0"
    assert "Pixiv" in result.stdout


@pytest.mark.parametrize("error", [
    UnicodeEncodeError("utf-8", "\ud800", 0, 1, "surrogates not allowed"),
    OSError(28, "No space left on device"),
    ValueError("not a plugin name"),
])
@pytest.mark.parametrize("command", [["add", IMAGE, "--yes"], ["remove", "alpha"], ["order", "beta"]])
def test_a_state_that_cannot_be_saved_is_an_error_message_and_the_old_state_stays(
    fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch, error: Exception, command: list[str]
) -> None:
    seed("alpha", "beta")
    serve(fake_docker)
    before = state.state_path().read_text()

    def boom(plugins: list[Plugin]) -> None:
        raise error

    monkeypatch.setattr(state, "save", boom)
    result = runner.invoke(app, ["plugin", *command])
    assert result.exit_code == 1
    assert result.stderr.startswith("error: ") and "cannot save" in result.stderr
    assert "Traceback" not in result.stderr and not isinstance(result.exception, (OSError, ValueError))
    assert state.state_path().read_text() == before


def test_a_digest_that_is_not_pinned_is_refused_before_the_manifest_is_read(fake_docker: FakeDocker) -> None:
    fake_docker.set(plugins={IMAGE: {"manifest": MANIFEST, "inspect": "[] latest"}})
    result = add(IMAGE, "--yes")
    assert result.exit_code == 1 and "pin" in result.stderr
    assert "run" not in fake_docker.commands()
    assert not state.state_path().exists()


def concurrently(monkeypatch: pytest.MonkeyPatch, change: Any) -> None:
    """Another anydm command changes the state while this one waits for the user's answer."""
    def confirm(*args: Any, **kwargs: Any) -> bool:
        change()
        return True

    monkeypatch.setattr(plugin_command.typer, "confirm", confirm)


def test_add_does_not_lose_a_plugin_another_command_added_while_it_waited(
    fake_docker: FakeDocker, terminal: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed("zeta")
    serve(fake_docker)
    concurrently(monkeypatch, lambda: state.save([*state.load(), Plugin(
        "other", "example/other@sha256:" + "e" * 64, "1", frozenset({"check"}), frozenset())]))
    assert add(IMAGE).exit_code == 0
    assert [p.name for p in installed()] == ["zeta", "other", "pixiv"]


def test_add_does_not_bring_back_a_plugin_another_command_removed_while_it_waited(
    fake_docker: FakeDocker, terminal: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed("zeta", "gone")
    serve(fake_docker)
    concurrently(monkeypatch, lambda: state.save([p for p in state.load() if p.name != "gone"]))
    assert add(IMAGE).exit_code == 0
    assert [p.name for p in installed()] == ["zeta", "pixiv"]


def test_add_refuses_when_the_same_plugin_changed_while_it_waited(
    fake_docker: FakeDocker, terminal: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed("pixiv", permissions=frozenset({"network"}))
    serve(fake_docker)
    concurrently(monkeypatch, lambda: seed("pixiv", permissions=frozenset()))
    result = add(IMAGE)
    assert result.exit_code == 1 and "changed" in result.stderr
    assert installed()[0].permissions == frozenset()  # what the other command wrote stays


def test_remove_with_image_does_not_bring_back_a_plugin_removed_meanwhile(
    fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.plugin.runner import PluginRunner

    seed("alpha", "beta", "gamma")
    real = PluginRunner.remove_image

    def slow(self: PluginRunner, image: str) -> None:
        state.save([p for p in state.load() if p.name != "beta"])  # another command, during the rmi
        real(self, image)

    monkeypatch.setattr(PluginRunner, "remove_image", slow)
    assert runner.invoke(app, ["plugin", "remove", "alpha", "--image"]).exit_code == 0
    assert [p.name for p in installed()] == ["gamma"]


# -- list ---------------------------------------------------------------------------------------------------------

def test_list_shows_the_routing_order_with_media_last_as_built_in(fake_docker: FakeDocker) -> None:
    seed("zeta", "alpha")
    result = runner.invoke(app, ["plugin", "list"])
    assert result.exit_code == 0, result.stderr
    names = [line.split()[0] for line in result.stdout.splitlines()]
    assert names == ["zeta", "alpha", "media"]
    last = result.stdout.splitlines()[-1]
    assert "built-in" in last and "check,download,info" in last and "network,cookies" in last
    first = result.stdout.splitlines()[0]
    assert "0.1" in first and "check,download" in first and "network" in first and "example/zeta@sha256:" in first
    assert fake_docker.calls() == []


def test_list_with_nothing_installed_shows_media_only(fake_docker: FakeDocker) -> None:
    result = runner.invoke(app, ["plugin", "list"])
    assert [line.split()[0] for line in result.stdout.splitlines()] == ["media"]


def test_list_json(fake_docker: FakeDocker) -> None:
    seed("zeta", permissions=frozenset({"network", "cookies"}))
    result = runner.invoke(app, ["plugin", "list", "--json"])
    assert result.exit_code == 0
    [zeta, media] = json.loads(result.stdout)
    assert zeta == {"name": "zeta", "version": "0.1", "capabilities": ["check", "download"],
                    "permissions": ["network", "cookies"], "image": "example/zeta@sha256:" + "d" * 64, "builtin": False}
    assert media["name"] == "media" and media["builtin"] is True and media["version"] == "built-in"
    assert media["capabilities"] == ["check", "download", "info"] and media["permissions"] == ["network", "cookies"]


def test_list_cleans_the_version(fake_docker: FakeDocker) -> None:
    state.save([Plugin("zeta", "example/zeta@sha256:" + "d" * 64, "1.0\x1b[31m\u202eevil", frozenset({"check"}),
                       frozenset())])
    for argv in (["plugin", "list"], ["plugin", "list", "--json"]):
        result = runner.invoke(app, argv)
        assert "\x1b" not in result.stdout and "\u202e" not in result.stdout


def test_list_with_a_corrupt_file_exits_2(fake_docker: FakeDocker) -> None:
    state.state_path().parent.mkdir(parents=True)
    state.state_path().write_text("broken [")
    result = runner.invoke(app, ["plugin", "list"])
    assert result.exit_code == 2 and "plugins.toml" in result.stderr


# -- remove -------------------------------------------------------------------------------------------------------

def test_remove_deletes_the_entry_and_keeps_the_others(fake_docker: FakeDocker) -> None:
    seed("zeta", "alpha", "beta")
    result = runner.invoke(app, ["plugin", "remove", "alpha"])
    assert result.exit_code == 0, result.stderr
    assert [p.name for p in installed()] == ["zeta", "beta"]
    assert "rmi" not in fake_docker.commands()


def test_remove_with_image_runs_rmi_on_the_pinned_reference(fake_docker: FakeDocker) -> None:
    seed("alpha", image=DIGEST)
    result = runner.invoke(app, ["plugin", "remove", "alpha", "--image"])
    assert result.exit_code == 0, result.stderr
    assert ["rmi", "--", DIGEST] in fake_docker.calls()
    assert installed() == []


def test_remove_with_image_that_docker_refuses_keeps_the_entry_and_exits_1(fake_docker: FakeDocker) -> None:
    seed("alpha", image=DIGEST)
    fake_docker.set(rmi="fail")
    result = runner.invoke(app, ["plugin", "remove", "alpha", "--image"])
    assert result.exit_code == 1 and "conflict" in result.stderr and "not removed" in result.stderr
    assert [p.name for p in installed()] == ["alpha"]


def test_remove_with_image_without_docker_keeps_the_entry_and_exits_1_naming_docker(fake_docker: FakeDocker) -> None:
    seed("alpha", image=DIGEST)
    fake_docker.set(version="fail")
    result = runner.invoke(app, ["plugin", "remove", "alpha", "--image"])
    assert result.exit_code == 1 and "Docker" in result.stderr
    assert [p.name for p in installed()] == ["alpha"]


def test_remove_with_image_keeps_an_image_that_another_plugin_uses(fake_docker: FakeDocker) -> None:
    state.save([Plugin(n, DIGEST, "1", frozenset({"check"}), frozenset()) for n in ("alpha", "beta")])
    result = runner.invoke(app, ["plugin", "remove", "alpha", "--image"])
    assert result.exit_code == 0, result.stderr
    assert "rmi" not in fake_docker.commands()
    assert "still used by beta" in result.stdout and "removed image" not in result.stdout
    assert [p.name for p in installed()] == ["beta"]


def test_remove_with_image_that_is_already_gone_just_removes_the_entry(fake_docker: FakeDocker) -> None:
    seed("alpha", image=DIGEST)
    fake_docker.set(plugins={DIGEST: {"present": False}})
    result = runner.invoke(app, ["plugin", "remove", "alpha", "--image"])
    assert result.exit_code == 0, result.stderr
    assert "rmi" not in fake_docker.commands() and "not present" in result.stdout
    assert installed() == []


def test_remove_media_exits_2(fake_docker: FakeDocker) -> None:
    seed("alpha")
    result = runner.invoke(app, ["plugin", "remove", "media"])
    assert result.exit_code == 2 and "built in" in result.stderr
    assert [p.name for p in installed()] == ["alpha"]


def test_remove_an_unknown_plugin_exits_2(fake_docker: FakeDocker) -> None:
    result = runner.invoke(app, ["plugin", "remove", "nope"])
    assert result.exit_code == 2 and "nope" in result.stderr


# -- order --------------------------------------------------------------------------------------------------------

def test_order_puts_the_listed_plugins_first_and_keeps_the_rest_in_order(fake_docker: FakeDocker) -> None:
    seed("a1", "b1", "c1", "d1")
    result = runner.invoke(app, ["plugin", "order", "c1", "b1"])
    assert result.exit_code == 0, result.stderr
    assert [p.name for p in installed()] == ["c1", "b1", "a1", "d1"]


def test_order_with_every_plugin_reverses_them(fake_docker: FakeDocker) -> None:
    seed("a1", "b1")
    runner.invoke(app, ["plugin", "order", "b1", "a1"])
    assert [p.name for p in installed()] == ["b1", "a1"]
    result = runner.invoke(app, ["plugin", "list"])
    assert [line.split()[0] for line in result.stdout.splitlines()] == ["b1", "a1", "media"]


def test_order_ignores_a_name_given_twice(fake_docker: FakeDocker) -> None:
    seed("a1", "b1", "c1")
    runner.invoke(app, ["plugin", "order", "c1", "c1"])
    assert [p.name for p in installed()] == ["c1", "a1", "b1"]


def test_order_with_an_unknown_name_exits_2_and_changes_nothing(fake_docker: FakeDocker) -> None:
    seed("a1", "b1")
    result = runner.invoke(app, ["plugin", "order", "b1", "ghost"])
    assert result.exit_code == 2 and "ghost" in result.stderr
    assert [p.name for p in installed()] == ["a1", "b1"]


def test_order_cannot_place_media(fake_docker: FakeDocker) -> None:
    seed("a1")
    result = runner.invoke(app, ["plugin", "order", "media", "a1"])
    assert result.exit_code == 2 and "media" in result.stderr


def test_order_needs_at_least_one_name() -> None:
    assert runner.invoke(app, ["plugin", "order"]).exit_code == 2
