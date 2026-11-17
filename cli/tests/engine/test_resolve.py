from pathlib import Path

import pytest
from src import engine
from src.core.errors import DownloadError, UsageError
from src.engine import Kind, Route, resolve

from tests.conftest import FakeDocker

REAL_MEDIA_SUPPORTED = engine._media_supported
YOUTUBE = "https://www.youtube.com/watch?v=XYZ"


@pytest.fixture(autouse=True)
def _fake_media(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(engine, "_media_supported", lambda url: "youtube.com" in url)


def test_magnet_is_torrent() -> None:
    assert resolve("magnet:?xt=urn:btih:abc", None).kind is Kind.TORRENT


def test_existing_local_torrent_file_is_torrent(tmp_path: Path) -> None:
    f = tmp_path / "a.torrent"
    f.write_bytes(b"d")
    assert resolve(str(f), None).kind is Kind.TORRENT


def test_missing_local_torrent_file_is_an_error(tmp_path: Path) -> None:
    with pytest.raises(UsageError):
        resolve(str(tmp_path / "nope.torrent"), None)


def test_torrent_url_is_torrent() -> None:
    assert resolve("https://example.com/x/a.torrent?dl=1", None).kind is Kind.TORRENT


def test_media_site_url_is_media() -> None:
    assert resolve(YOUTUBE, None).kind is Kind.MEDIA


def test_plain_url_is_http() -> None:
    assert resolve("https://example.com/ubuntu.iso", None).kind is Kind.HTTP
    assert resolve("http://example.com/ubuntu.iso", None).kind is Kind.HTTP


def test_other_scheme_is_a_usage_error() -> None:
    with pytest.raises(UsageError):
        resolve("ftp://x", None)


def test_forced_kind_overrides_classification() -> None:
    assert resolve(YOUTUBE, Kind.HTTP).kind is Kind.HTTP


@pytest.fixture
def no_local_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    """yt-dlp is not importable on the host, and the real classification runs (not the autouse fake)."""
    monkeypatch.undo()
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    engine._docker_runner.cache_clear()
    engine.take_notices()


def test_local_media_check_is_false_without_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.undo()
    monkeypatch.setattr(engine.importlib.util, "find_spec", lambda name: None)
    assert engine._local_media_supported(YOUTUBE) is False


def test_container_answer_decides_media_or_http(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=True)
    assert resolve(YOUTUBE, None).kind is Kind.MEDIA
    fake_docker.set(check=False)
    assert resolve("https://example.com/a.bin", None).kind is Kind.HTTP


def test_one_container_call_per_url(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=True)
    resolve(YOUTUBE, None)
    resolve(YOUTUBE, None)
    assert [c[0] for c in fake_docker.calls()].count("run") == 1


@pytest.mark.parametrize("source", ["magnet:?xt=urn:btih:abc", "https://example.com/x/a.torrent"])
def test_torrents_make_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker, source: str) -> None:
    assert resolve(source, None).kind is Kind.TORRENT
    assert fake_docker.calls() == []


def test_a_local_torrent_file_makes_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker, tmp_path: Path) -> None:
    f = tmp_path / "a.torrent"
    f.write_bytes(b"d")
    assert resolve(str(f), None).kind is Kind.TORRENT
    assert fake_docker.calls() == []


def test_forced_kind_makes_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    assert resolve(YOUTUBE, Kind.HTTP).kind is Kind.HTTP
    assert resolve(YOUTUBE, Kind.MEDIA).kind is Kind.MEDIA
    assert fake_docker.calls() == []


def test_without_docker_a_plain_url_is_http_with_one_notice(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    assert resolve("https://example.com/a.bin", None).kind is Kind.HTTP
    assert resolve("https://example.com/b.bin", None).kind is Kind.HTTP
    [notice] = engine.take_notices()
    assert "Docker" in notice and "skipped" in notice
    assert engine.take_notices() == []


def test_forced_media_without_docker_is_an_error_naming_docker(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    with pytest.raises(DownloadError, match=r"Docker.*anydm doctor"):
        engine.engine_for(Kind.MEDIA)


def test_engine_for_media_is_the_container_engine_without_yt_dlp(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    from src.engine.media_docker import DockerMediaEngine

    assert isinstance(engine.engine_for(Kind.MEDIA), DockerMediaEngine)


def test_with_yt_dlp_the_in_process_engine_is_used_and_docker_is_not_touched(fake_docker: FakeDocker) -> None:
    from src.engine.media import MediaEngine

    assert isinstance(engine.engine_for(Kind.MEDIA), MediaEngine)
    assert REAL_MEDIA_SUPPORTED("https://example.com/a.bin") is False
    assert fake_docker.calls() == []


@pytest.mark.parametrize("source", ["notaurl", "http://", "ftp://h/x", "https:///x"])
def test_forced_http_needs_an_http_url_with_a_host(source: str) -> None:
    with pytest.raises(UsageError):
        resolve(source, Kind.HTTP)


def test_http_without_host_is_not_resolved() -> None:
    with pytest.raises(UsageError):
        resolve("http://", None)


@pytest.mark.parametrize("scheme", ["MAGNET", "Magnet", "magnet"])
def test_a_magnet_is_a_torrent_whatever_the_case_of_its_scheme(scheme: str) -> None:
    assert resolve(f"{scheme}:?xt=urn:btih:" + "a" * 40, None).kind is Kind.TORRENT


def test_a_protocol_mismatch_is_an_error_not_a_fallback_to_http(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check_lines=[{"type": "hello", "protocol": 99}, {"type": "supported", "value": True}])
    with pytest.raises(DownloadError, match="update the media image"):
        resolve("https://example.com/a.bin", None)
    assert engine.take_notices() == []


def test_a_pull_inside_the_check_leaves_a_notice(
    no_local_yt_dlp: None, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", "ghcr.io/x/anydm-media:1")
    engine._docker_runner.cache_clear()
    fake_docker.set(image=False, check=True)
    assert resolve(YOUTUBE, None).kind is Kind.MEDIA
    [notice] = engine.take_notices()
    assert "pulling ghcr.io/x/anydm-media:1" in notice


def test_cached_only_resolution_never_runs_docker(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=True)
    assert resolve(YOUTUBE, None, cached_only=True).kind is Kind.HTTP  # not asked yet: no answer, no docker
    assert fake_docker.calls() == []
    assert resolve(YOUTUBE, None).kind is Kind.MEDIA
    runs = fake_docker.commands().count("run")
    assert resolve(YOUTUBE, None, cached_only=True).kind is Kind.MEDIA
    assert fake_docker.commands().count("run") == runs


def test_cached_only_resolution_treats_a_failed_check_as_http(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check_lines=[], check_exit=1, check_stderr="boom")
    assert resolve(YOUTUBE, None).kind is Kind.HTTP
    n = len(fake_docker.calls())
    assert resolve(YOUTUBE, None, cached_only=True).kind is Kind.HTTP
    assert len(fake_docker.calls()) == n


def test_cached_only_resolution_keeps_the_other_rules(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    assert resolve("magnet:?xt=urn:btih:abc", None, cached_only=True).kind is Kind.TORRENT
    with pytest.raises(UsageError):
        resolve("ftp://x", None, cached_only=True)
    assert fake_docker.calls() == []


def test_the_pending_notices_are_capped(no_local_yt_dlp: None) -> None:
    for i in range(engine.MAX_NOTICES * 3):
        engine._add_notice(f"note {i}")
    assert len(engine.take_notices()) == engine.MAX_NOTICES


def test_media_routes_carry_the_builtin_plugin_name() -> None:
    assert resolve(YOUTUBE, Kind.MEDIA) == Route(Kind.MEDIA, "media")
    assert resolve(YOUTUBE, Kind.HTTP) == Route(Kind.HTTP)
    assert resolve("magnet:?xt=urn:btih:abc", None) == Route(Kind.TORRENT)


def test_engine_for_takes_a_route_and_a_kind(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    from src.plugin.engine import PluginEngine

    assert isinstance(engine.engine_for(Route(Kind.MEDIA, "media")), PluginEngine)
    assert isinstance(engine.engine_for(Route(Kind.PLUGIN, "media")), PluginEngine)


def test_an_unknown_plugin_route_is_an_error() -> None:
    with pytest.raises(DownloadError, match="unknown plugin 'nope'"):
        engine.engine_for(Route(Kind.PLUGIN, "nope"))


def test_a_route_is_labelled_by_its_plugin_or_its_kind() -> None:
    assert Route(Kind.MEDIA, "media").label == "media"
    assert Route(Kind.HTTP).label == "http"


# -- installed plugins (routing, --plugin) ------------------------------------------------------------------------

A_IMAGE = "ghcr.io/acme/a@sha256:" + "a" * 64
B_IMAGE = "ghcr.io/acme/b@sha256:" + "b" * 64
PLUGIN_URL = "https://example.org/thing/1"


def install(*plugins: tuple[str, str, tuple[str, ...], tuple[str, ...]]) -> None:
    from src.plugin import state
    from src.plugin.model import Plugin

    state.save([Plugin(n, image, "1", frozenset(caps), frozenset(perms)) for n, image, caps, perms in plugins])


ALL = ("check", "download", "info")


@pytest.fixture
def two_plugins(fake_docker: FakeDocker) -> FakeDocker:
    install(("alpha", A_IMAGE, ALL, ("network",)), ("beta", B_IMAGE, ALL, ("network", "cookies")))
    fake_docker.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": True}})
    return fake_docker


def checks(fake: FakeDocker) -> list[str]:
    return [c[c.index("--") + 1] for c in fake.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]]


def test_a_link_goes_to_the_first_plugin_that_says_yes_with_one_check_each(two_plugins: FakeDocker) -> None:
    assert resolve(PLUGIN_URL, None) == Route(Kind.PLUGIN, "beta")
    assert checks(two_plugins) == [A_IMAGE, B_IMAGE]


def test_the_same_link_again_makes_no_call(two_plugins: FakeDocker) -> None:
    resolve(PLUGIN_URL, None)
    calls = len(two_plugins.calls())
    assert resolve(PLUGIN_URL, None) == Route(Kind.PLUGIN, "beta")
    assert len(two_plugins.calls()) == calls


def test_a_plugin_is_asked_before_media(two_plugins: FakeDocker) -> None:
    assert resolve(YOUTUBE, None).plugin == "beta"  # media would say yes too


def test_when_no_plugin_says_yes_media_and_then_http_decide(two_plugins: FakeDocker) -> None:
    two_plugins.set(plugins={A_IMAGE: {"check": False}, B_IMAGE: {"check": False}})
    assert resolve(YOUTUBE, None) == Route(Kind.MEDIA, "media")
    assert resolve("https://example.com/a.bin", None) == Route(Kind.HTTP)


@pytest.mark.parametrize("source", ["magnet:?xt=urn:btih:abc", "https://example.com/x/a.torrent"])
def test_a_torrent_asks_no_plugin(two_plugins: FakeDocker, source: str) -> None:
    assert resolve(source, None).kind is Kind.TORRENT
    assert two_plugins.calls() == []


def test_a_local_torrent_file_and_other_schemes_ask_no_plugin(two_plugins: FakeDocker, tmp_path: Path) -> None:
    f = tmp_path / "a.torrent"
    f.write_bytes(b"d")
    assert resolve(str(f), None).kind is Kind.TORRENT
    with pytest.raises(UsageError):
        resolve("ftp://h/x", None)
    assert two_plugins.calls() == []


def test_forcing_a_kind_asks_no_plugin(two_plugins: FakeDocker) -> None:
    assert resolve(PLUGIN_URL, Kind.HTTP).kind is Kind.HTTP
    assert two_plugins.calls() == []


def test_a_plugin_that_times_out_is_skipped_with_one_notice(
    two_plugins: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.plugin import route

    monkeypatch.setattr(route, "CHECK_TIMEOUT", 0.4)
    engine.take_notices()
    two_plugins.set(plugins={A_IMAGE: {"check_lines": [{"type": "hello", "protocol": 3}], "check_hang": True},
                             B_IMAGE: {"check": True}})
    assert resolve(PLUGIN_URL, None).plugin == "beta"
    [note] = engine.take_notices()
    assert "alpha" in note and "timed out" in note


def test_cached_only_reads_only_what_was_asked(two_plugins: FakeDocker) -> None:
    assert resolve(PLUGIN_URL, None, cached_only=True) == Route(Kind.HTTP)
    assert two_plugins.calls() == []
    assert resolve(PLUGIN_URL, None) == Route(Kind.PLUGIN, "beta")
    calls = len(two_plugins.calls())
    assert resolve(PLUGIN_URL, None, cached_only=True) == Route(Kind.PLUGIN, "beta")
    assert len(two_plugins.calls()) == calls


def test_without_docker_the_link_is_http_with_one_notice(two_plugins: FakeDocker) -> None:
    engine.take_notices()
    two_plugins.set(version="fail")
    assert resolve(PLUGIN_URL, None) == Route(Kind.HTTP)
    assert resolve("https://example.org/other", None) == Route(Kind.HTTP)
    [note] = engine.take_notices()
    assert "Docker" in note and "anydm doctor" in note


def test_without_docker_and_without_yt_dlp_media_and_plugins_share_one_notice(
    no_local_yt_dlp: None, two_plugins: FakeDocker
) -> None:
    two_plugins.set(version="fail")
    assert resolve(PLUGIN_URL, None) == Route(Kind.HTTP)
    [note] = engine.take_notices()
    assert "Docker" in note and "media" in note and "plugin" in note


def test_no_plugins_installed_means_no_extra_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=False)
    resolve("https://example.com/a.bin", None)
    assert [c[0] for c in fake_docker.calls()].count("run") == 1  # media's check only


def test_a_corrupt_plugin_file_is_a_usage_error_for_routing(fake_docker: FakeDocker) -> None:
    from src.plugin import state

    state.state_path().parent.mkdir(parents=True, exist_ok=True)
    state.state_path().write_text("not toml [")
    with pytest.raises(UsageError, match=r"plugins\.toml"):
        resolve(PLUGIN_URL, None)


def test_forcing_a_plugin_skips_check(two_plugins: FakeDocker) -> None:
    assert resolve(PLUGIN_URL, None, plugin="alpha") == Route(Kind.PLUGIN, "alpha")
    assert checks(two_plugins) == []
    assert "run" not in two_plugins.commands()


def test_forcing_a_plugin_takes_any_source(two_plugins: FakeDocker) -> None:
    assert resolve("magnet:?xt=urn:btih:abc", None, plugin="alpha") == Route(Kind.PLUGIN, "alpha")


def test_forcing_an_unknown_plugin_is_a_usage_error(two_plugins: FakeDocker) -> None:
    with pytest.raises(UsageError, match="no plugin named 'nope'"):
        resolve(PLUGIN_URL, None, plugin="nope")


def test_forcing_a_plugin_without_the_capability_is_a_usage_error(fake_docker: FakeDocker) -> None:
    install(("quiet", A_IMAGE, ("check", "download"), ()), ("silent", B_IMAGE, ("check", "info"), ()))
    with pytest.raises(UsageError, match="plugin quiet cannot show info"):
        resolve(PLUGIN_URL, None, plugin="quiet", need="info")
    with pytest.raises(UsageError, match="plugin silent cannot download"):
        resolve(PLUGIN_URL, None, plugin="silent")
    assert resolve(PLUGIN_URL, None, plugin="quiet") == Route(Kind.PLUGIN, "quiet")


def test_a_link_skips_a_plugin_that_lacks_the_needed_capability(fake_docker: FakeDocker) -> None:
    install(("lookup", A_IMAGE, ("check", "info"), ()), ("quiet", B_IMAGE, ("check", "download"), ()),
            ("both", "ghcr.io/acme/c@sha256:" + "c" * 64, ALL, ()))
    fake_docker.set(plugins={A_IMAGE: {"check": True}, B_IMAGE: {"check": True},
                             "ghcr.io/acme/c@sha256:" + "c" * 64: {"check": True}})
    assert resolve(PLUGIN_URL, None) == Route(Kind.PLUGIN, "quiet")  # a download: lookup cannot
    assert resolve(PLUGIN_URL, None, need="info") == Route(Kind.PLUGIN, "lookup")
    assert checks(fake_docker) == [B_IMAGE, A_IMAGE]  # each plugin is asked only when it could serve


def test_a_link_claimed_only_by_plugins_that_cannot_serve_it_falls_through(fake_docker: FakeDocker) -> None:
    install(("lookup", A_IMAGE, ("check", "info"), ()))
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    assert resolve("https://example.com/a.bin", None) == Route(Kind.HTTP)
    assert checks(fake_docker) == []


def test_forcing_a_plugin_without_docker_is_an_error_naming_docker(two_plugins: FakeDocker) -> None:
    two_plugins.set(version="fail")
    with pytest.raises(DownloadError, match=r"plugin alpha needs Docker.*anydm doctor") as e:
        resolve(PLUGIN_URL, None, plugin="alpha")
    assert e.value.exit_code == 1


def test_forcing_a_plugin_for_the_cache_only_resolution_never_runs_docker(two_plugins: FakeDocker) -> None:
    two_plugins.set(version="fail")
    assert resolve(PLUGIN_URL, None, plugin="alpha", cached_only=True) == Route(Kind.PLUGIN, "alpha")
    assert two_plugins.calls() == []


def test_forcing_media_by_name_or_by_kind_is_the_same(fake_docker: FakeDocker) -> None:
    assert resolve(YOUTUBE, None, plugin="media") == Route(Kind.MEDIA, "media")
    assert resolve(YOUTUBE, Kind.MEDIA) == Route(Kind.MEDIA, "media")
    assert resolve(YOUTUBE, Kind.MEDIA, plugin="media") == Route(Kind.MEDIA, "media")
    assert fake_docker.calls() == []


def test_a_kind_and_a_plugin_together_are_a_usage_error(two_plugins: FakeDocker) -> None:
    with pytest.raises(UsageError, match="--kind and --plugin"):
        resolve(PLUGIN_URL, Kind.HTTP, plugin="alpha")
    with pytest.raises(UsageError, match="--kind and --plugin"):
        resolve(PLUGIN_URL, Kind.MEDIA, plugin="alpha")


def test_engine_for_a_plugin_route_runs_that_plugin_by_its_pinned_image(two_plugins: FakeDocker) -> None:
    from src.plugin.engine import PluginEngine

    built = engine.engine_for(Route(Kind.PLUGIN, "beta"))
    assert isinstance(built, PluginEngine)
    assert built._plugin.image == B_IMAGE and built._plugin.permissions == {"network", "cookies"}


def test_engine_for_a_plugin_without_docker_names_docker(two_plugins: FakeDocker) -> None:
    two_plugins.set(version="fail")
    with pytest.raises(DownloadError, match=r"plugin beta needs Docker.*anydm doctor"):
        engine.engine_for(Route(Kind.PLUGIN, "beta"))


def test_cookies_go_only_to_routes_whose_plugin_has_the_permission(two_plugins: FakeDocker) -> None:
    assert engine.route_takes_cookies(Route(Kind.MEDIA, "media"))
    assert engine.route_takes_cookies(Route(Kind.PLUGIN, "beta"))
    assert not engine.route_takes_cookies(Route(Kind.PLUGIN, "alpha"))
    assert not engine.route_takes_cookies(Route(Kind.PLUGIN, "gone"))
    assert not engine.route_takes_cookies(Route(Kind.HTTP))
    assert not engine.route_takes_cookies(Route(Kind.TORRENT))
