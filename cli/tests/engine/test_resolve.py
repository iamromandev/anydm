from pathlib import Path

import pytest
from src import engine
from src.core.errors import UsageError
from src.engine import Kind, resolve

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


def test_media_check_is_false_without_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.undo()
    monkeypatch.setattr(engine.importlib.util, "find_spec", lambda name: None)
    assert engine._media_supported(YOUTUBE) is False


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
