import os
import stat
import tomllib
from pathlib import Path

import pytest
from src.config.settings import Settings
from src.core.errors import UsageError
from src.plugin import state
from src.plugin.model import Plugin


@pytest.fixture(autouse=True)
def config_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    return tmp_path / "xdg"


def make(name: str, image: str = "ghcr.io/acme/p@sha256:" + "a" * 64, **kw: object) -> Plugin:
    base: dict[str, object] = {
        "version": "1.0", "capabilities": frozenset({"check", "download"}), "permissions": frozenset({"network"}),
    }
    return Plugin(name, image, **{**base, **kw})  # ty: ignore[invalid-argument-type]


def test_the_state_lives_beside_config_toml(config_home: Path) -> None:
    assert state.state_path() == config_home / "anydm" / "plugins.toml"


def test_the_state_path_falls_back_to_home_config(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.delenv("XDG_CONFIG_HOME")
    monkeypatch.setenv("HOME", str(tmp_path))
    assert state.state_path() == tmp_path / ".config" / "anydm" / "plugins.toml"


def test_without_a_file_nothing_is_installed() -> None:
    assert state.load() == []


def test_save_then_load_round_trips_in_order() -> None:
    plugins = [make("zeta"), make("alpha", permissions=frozenset({"network", "cookies"}), version="2.1")]
    state.save(plugins)
    assert state.load() == plugins


def test_the_file_looks_like_the_documented_example() -> None:
    state.save([make("pixiv", capabilities=frozenset({"check", "download", "info"}),
                     permissions=frozenset({"network", "cookies"}))])
    data = tomllib.loads(state.state_path().read_text())
    assert data == {
        "order": ["pixiv"],
        "plugins": {"pixiv": {
            "image": "ghcr.io/acme/p@sha256:" + "a" * 64, "version": "1.0",
            "capabilities": ["check", "download", "info"], "permissions": ["cookies", "network"],
        }},
    }


def test_the_file_is_private_and_no_temp_file_is_left() -> None:
    state.save([make("alpha")])
    assert stat.S_IMODE(state.state_path().stat().st_mode) == 0o600
    assert [p.name for p in state.state_path().parent.iterdir()] == ["plugins.toml"]


def test_the_mode_is_0600_even_under_a_loose_umask() -> None:
    old = os.umask(0)
    try:
        state.save([make("alpha")])
    finally:
        os.umask(old)
    assert stat.S_IMODE(state.state_path().stat().st_mode) == 0o600


def test_saving_again_replaces_the_file_atomically(monkeypatch: pytest.MonkeyPatch) -> None:
    state.save([make("alpha")])
    before = state.state_path().read_text()
    real = os.replace
    seen: list[str] = []

    def spy(src: str | Path, dst: str | Path) -> None:
        seen.append(Path(src).read_text())  # the new content is complete when it is moved into place
        assert state.state_path().read_text() == before
        real(src, dst)

    monkeypatch.setattr(os, "replace", spy)
    state.save([make("beta")])
    assert "beta" in seen[0] and "alpha" not in seen[0]
    assert [p.name for p in state.load()] == ["beta"]


def test_a_failed_write_keeps_the_old_file_and_leaves_no_temp(monkeypatch: pytest.MonkeyPatch) -> None:
    state.save([make("alpha")])
    before = state.state_path().read_text()

    def boom(src: str | Path, dst: str | Path) -> None:
        raise OSError("disk full")

    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(OSError, match="disk full"):
        state.save([make("beta")])
    assert state.state_path().read_text() == before
    assert [p.name for p in state.state_path().parent.iterdir()] == ["plugins.toml"]


@pytest.mark.parametrize("version", [
    'quote " inside', "back\\slash", "tab\there", "new\nline", "unicode é中\U0001f600", "del\x7f", "nul\x00x",
    "'single'", '"""', "sep" + chr(0x2028),
])
def test_strings_survive_the_writer(version: str) -> None:
    state.save([make("alpha", version=version)])
    tomllib.loads(state.state_path().read_text())  # still valid TOML
    [loaded] = state.load()
    assert loaded.version == version


def test_an_image_with_odd_characters_is_refused_on_save() -> None:
    with pytest.raises(ValueError, match="image"):
        state.save([make("alpha", image='x"\ny = 1')])


def test_the_listed_order_wins_over_the_table_order() -> None:
    path = state.state_path()
    path.parent.mkdir(parents=True)
    path.write_text(
        'order = ["b1"]\n[plugins.a1]\nimage = "a/p@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n'
        '[plugins.b1]\nimage = "b/p@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n'
    )
    assert [p.name for p in state.load()] == ["b1", "a1"]


@pytest.mark.parametrize("text", [
    "this is not toml [",
    "order = 3\n",
    'order = ["ghost"]\n',
    "plugins = 3\n",
    "[plugins.pixiv]\nimage = 3\n",
    '[plugins.pixiv]\nimage = "x"\nversion = "1"\ncapabilities = []\npermissions = []\n',
    '[plugins.pixiv]\nimage = "x"\nversion = "1"\ncapabilities = ["fly"]\npermissions = []\n',
    '[plugins.pixiv]\nimage = "x"\nversion = "1"\ncapabilities = ["check"]\npermissions = ["root"]\n',
    '[plugins.Bad_Name]\nimage = "x"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n',
    '[plugins.media]\nimage = "x"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n',
    '[plugins.pixiv]\nimage = "-rm"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n',
    '[plugins.pixiv]\nimage = "x"\ncapabilities = ["check"]\npermissions = []\n',
])
def test_a_corrupt_file_is_a_usage_error_naming_the_path(text: str) -> None:
    path = state.state_path()
    path.parent.mkdir(parents=True)
    path.write_text(text)
    with pytest.raises(UsageError, match=r"plugins\.toml") as e:
        state.load()
    assert str(path) in str(e.value)


def test_a_file_that_is_not_utf8_is_a_usage_error() -> None:
    path = state.state_path()
    path.parent.mkdir(parents=True)
    path.write_bytes(b"\xff\xfe\x00")
    with pytest.raises(UsageError, match=r"plugins\.toml"):
        state.load()


def test_routing_is_the_installed_plugins_then_media() -> None:
    state.save([make("zeta"), make("alpha")])
    routing = state.routing(Settings(media_image="anydm-media:9"))
    assert [p.name for p in routing] == ["zeta", "alpha", "media"]
    assert routing[-1].builtin and routing[-1].image == "anydm-media:9"


def test_plugin_state_is_never_read_from_the_environment_or_dotenv(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("ANYDM_PLUGINS", "evil")
    monkeypatch.chdir(tmp_path)
    (tmp_path / ".env").write_text("ANYDM_PLUGINS=evil\n")
    assert state.routing(Settings())[-1].name == "media"
    assert [p.name for p in state.routing(Settings())] == ["media"]


UNPINNED = ["evil:latest", "ghcr.io/acme/p:1", "ghcr.io/acme/p", "sha256:abc", "ghcr.io/acme/p@sha256:" + "a" * 63,
            "ghcr.io/acme/p@sha256:" + "A" * 64, "ghcr.io/acme/p@sha512:" + "a" * 64]


@pytest.mark.parametrize("image", UNPINNED)
def test_load_refuses_an_image_that_is_not_pinned_naming_the_entry(image: str) -> None:
    path = state.state_path()
    path.parent.mkdir(parents=True)
    path.write_text(
        f'[plugins.evil]\nimage = "{image}"\nversion = "1"\ncapabilities = ["check"]\npermissions = []\n'
    )
    with pytest.raises(UsageError, match=r"plugin 'evil'.*pinned") as e:
        state.load()
    assert str(path) in str(e.value)


@pytest.mark.parametrize("image", UNPINNED)
def test_save_refuses_an_image_that_is_not_pinned(image: str) -> None:
    with pytest.raises(ValueError, match="pinned"):
        state.save([make("alpha", image=image)])
    assert not state.state_path().exists()


@pytest.mark.parametrize("image", ["sha256:" + "c" * 64, "localhost:5000/p:1@sha256:" + "d" * 64])
def test_a_bare_image_id_and_a_tagged_digest_reference_are_pinned(image: str) -> None:
    state.save([make("alpha", image=image)])
    assert [p.image for p in state.load()] == [image]


def test_the_directory_is_synced_after_the_rename(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: list[tuple[bool, bool]] = []  # (is a directory, the new file is already in place)
    real = os.fsync

    def spy(fd: int) -> None:
        seen.append((stat.S_ISDIR(os.fstat(fd).st_mode), state.state_path().exists()))
        real(fd)

    monkeypatch.setattr(os, "fsync", spy)
    state.save([make("alpha")])
    assert seen[-1] == (True, True)


def count_parses(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    parses: list[int] = []
    real = tomllib.loads

    def spy(text: str, **kw: object) -> dict[str, object]:
        parses.append(1)
        return real(text, **kw)  # ty: ignore[invalid-argument-type]

    monkeypatch.setattr(tomllib, "loads", spy)
    return parses


def test_an_unchanged_file_is_parsed_once(monkeypatch: pytest.MonkeyPatch) -> None:
    state.save([make("alpha")])
    parses = count_parses(monkeypatch)
    first = state.load()
    for _ in range(5):
        assert state.load() == first
    assert len(parses) == 1


def test_a_changed_file_is_read_again(monkeypatch: pytest.MonkeyPatch) -> None:
    state.save([make("alpha")])
    assert [p.name for p in state.load()] == ["alpha"]
    state.save([make("alpha"), make("beta")])  # this process's own write
    assert [p.name for p in state.load()] == ["alpha", "beta"]
    state.state_path().write_text(state.state_path().read_text().replace("beta", "gamma"))  # an edit in place
    assert [p.name for p in state.load()] == ["alpha", "gamma"]
    state.state_path().unlink()
    assert state.load() == []


def test_a_cached_list_cannot_be_changed_by_a_caller() -> None:
    state.save([make("alpha")])
    state.load().clear()
    assert [p.name for p in state.load()] == ["alpha"]


def test_a_corrupt_file_is_not_cached() -> None:
    state.save([make("alpha")])
    state.load()
    state.state_path().write_text("broken [")
    with pytest.raises(UsageError):
        state.load()
    state.save([make("beta")])
    assert [p.name for p in state.load()] == ["beta"]
