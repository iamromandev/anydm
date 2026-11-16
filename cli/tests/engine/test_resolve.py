from pathlib import Path

import pytest
from src import engine
from src.core.errors import DownloadError, UsageError
from src.engine import Kind, resolve

from tests.conftest import FakeDocker

REAL_MEDIA_SUPPORTED = engine._media_supported
YOUTUBE = "https://www.youtube.com/watch?v=XYZ"


@pytest.fixture(autouse=True)
def _fake_media(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(engine, "_media_supported", lambda url: "youtube.com" in url)


def test_magnet_is_torrent() -> None:
    assert resolve("magnet:?xt=urn:btih:abc", None) is Kind.TORRENT


def test_existing_local_torrent_file_is_torrent(tmp_path: Path) -> None:
    f = tmp_path / "a.torrent"
    f.write_bytes(b"d")
    assert resolve(str(f), None) is Kind.TORRENT


def test_missing_local_torrent_file_is_an_error(tmp_path: Path) -> None:
    with pytest.raises(UsageError):
        resolve(str(tmp_path / "nope.torrent"), None)


def test_torrent_url_is_torrent() -> None:
    assert resolve("https://example.com/x/a.torrent?dl=1", None) is Kind.TORRENT


def test_media_site_url_is_media() -> None:
    assert resolve(YOUTUBE, None) is Kind.MEDIA


def test_plain_url_is_http() -> None:
    assert resolve("https://example.com/ubuntu.iso", None) is Kind.HTTP
    assert resolve("http://example.com/ubuntu.iso", None) is Kind.HTTP


def test_other_scheme_is_a_usage_error() -> None:
    with pytest.raises(UsageError):
        resolve("ftp://x", None)


def test_forced_kind_overrides_classification() -> None:
    assert resolve(YOUTUBE, Kind.HTTP) is Kind.HTTP


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
    assert resolve(YOUTUBE, None) is Kind.MEDIA
    fake_docker.set(check=False)
    assert resolve("https://example.com/a.bin", None) is Kind.HTTP


def test_one_container_call_per_url(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=True)
    resolve(YOUTUBE, None)
    resolve(YOUTUBE, None)
    assert [c[0] for c in fake_docker.calls()].count("run") == 1


@pytest.mark.parametrize("source", ["magnet:?xt=urn:btih:abc", "https://example.com/x/a.torrent"])
def test_torrents_make_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker, source: str) -> None:
    assert resolve(source, None) is Kind.TORRENT
    assert fake_docker.calls() == []


def test_a_local_torrent_file_makes_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker, tmp_path: Path) -> None:
    f = tmp_path / "a.torrent"
    f.write_bytes(b"d")
    assert resolve(str(f), None) is Kind.TORRENT
    assert fake_docker.calls() == []


def test_forced_kind_makes_no_docker_call(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    assert resolve(YOUTUBE, Kind.HTTP) is Kind.HTTP
    assert resolve(YOUTUBE, Kind.MEDIA) is Kind.MEDIA
    assert fake_docker.calls() == []


def test_without_docker_a_plain_url_is_http_with_one_notice(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    assert resolve("https://example.com/a.bin", None) is Kind.HTTP
    assert resolve("https://example.com/b.bin", None) is Kind.HTTP
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
    assert resolve(f"{scheme}:?xt=urn:btih:" + "a" * 40, None) is Kind.TORRENT


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
    assert resolve(YOUTUBE, None) is Kind.MEDIA
    [notice] = engine.take_notices()
    assert "pulling ghcr.io/x/anydm-media:1" in notice


def test_cached_only_resolution_never_runs_docker(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check=True)
    assert resolve(YOUTUBE, None, cached_only=True) is Kind.HTTP  # not asked yet: no answer, no docker
    assert fake_docker.calls() == []
    assert resolve(YOUTUBE, None) is Kind.MEDIA
    runs = fake_docker.commands().count("run")
    assert resolve(YOUTUBE, None, cached_only=True) is Kind.MEDIA
    assert fake_docker.commands().count("run") == runs


def test_cached_only_resolution_treats_a_failed_check_as_http(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    fake_docker.set(check_lines=[], check_exit=1, check_stderr="boom")
    assert resolve(YOUTUBE, None) is Kind.HTTP
    n = len(fake_docker.calls())
    assert resolve(YOUTUBE, None, cached_only=True) is Kind.HTTP
    assert len(fake_docker.calls()) == n


def test_cached_only_resolution_keeps_the_other_rules(no_local_yt_dlp: None, fake_docker: FakeDocker) -> None:
    assert resolve("magnet:?xt=urn:btih:abc", None, cached_only=True) is Kind.TORRENT
    with pytest.raises(UsageError):
        resolve("ftp://x", None, cached_only=True)
    assert fake_docker.calls() == []


def test_the_pending_notices_are_capped(no_local_yt_dlp: None) -> None:
    for i in range(engine.MAX_NOTICES * 3):
        engine._add_notice(f"note {i}")
    assert len(engine.take_notices()) == engine.MAX_NOTICES
