import base64

import pytest
from src.core.error import Error
from src.core.type import Code
from src.lib.torrent.source import parse_source

MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=thing"
TORRENT_BYTES = b"d8:announce13:udd:tracker:80e"


def test_magnet_is_kept_as_text() -> None:
    source = parse_source(MAGNET)
    assert source.text == MAGNET
    assert source.blob is None
    assert source.is_blob is False


def test_magnet_is_recognised_case_insensitively() -> None:
    assert parse_source("MAGNET:?xt=urn:btih:abc").text == "MAGNET:?xt=urn:btih:abc"


def test_surrounding_whitespace_is_trimmed() -> None:
    assert parse_source(f"  {MAGNET}  ").text == MAGNET


def test_http_link_is_kept_as_text() -> None:
    url = "https://example.test/thing.torrent"
    assert parse_source(url).text == url


def test_base64_torrent_becomes_bytes() -> None:
    encoded = base64.b64encode(TORRENT_BYTES).decode()
    source = parse_source(encoded)
    assert source.blob == TORRENT_BYTES
    assert source.text is None
    assert source.is_blob is True


def test_data_url_prefix_is_stripped() -> None:
    encoded = base64.b64encode(TORRENT_BYTES).decode()
    source = parse_source(f"data:application/x-bittorrent;base64,{encoded}")
    assert source.blob == TORRENT_BYTES


def test_empty_input_is_rejected() -> None:
    with pytest.raises(Error) as caught:
        parse_source("   ")
    assert caught.value.code == Code.BAD_REQUEST


def test_garbage_is_rejected() -> None:
    with pytest.raises(Error) as caught:
        parse_source("not a torrent at all!!")
    assert caught.value.code == Code.BAD_REQUEST


def test_base64_of_something_that_is_not_a_torrent_is_rejected() -> None:
    encoded = base64.b64encode(b"just some text").decode()
    with pytest.raises(Error) as caught:
        parse_source(encoded)
    assert caught.value.code == Code.BAD_REQUEST
