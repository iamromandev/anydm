import base64

import pytest
from src.core.errors import UsageError
from src.engine.torrent.magnet import parse_magnet

IH = bytes(range(20))


def test_parses_a_40_hex_infohash_name_and_repeated_trackers() -> None:
    m = parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}&dn=My+Movie&tr=http%3A%2F%2Fa%2Fannounce&tr=udp%3A%2F%2Fb%3A1%2Fannounce")
    assert m.infohash == IH
    assert m.name == "My Movie"
    assert m.trackers == ["http://a/announce", "udp://b:1/announce"]


def test_parses_a_32_char_base32_infohash_in_either_case() -> None:
    b32 = base64.b32encode(IH).decode()
    assert len(b32) == 32
    assert parse_magnet(f"magnet:?xt=urn:btih:{b32}").infohash == IH
    assert parse_magnet(f"magnet:?xt=urn:btih:{b32.lower()}").infohash == IH


def test_hex_is_case_insensitive() -> None:
    assert parse_magnet(f"magnet:?xt=urn:btih:{IH.hex().upper()}").infohash == IH


def test_name_is_cleaned_and_optional() -> None:
    assert parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}").name is None
    m = parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}&dn=..%2F..%2Fetc%2Fpasswd")
    assert m.name == "passwd"


def test_a_hybrid_magnet_with_btih_and_btmh_uses_the_btih() -> None:
    m = parse_magnet(f"magnet:?xt=urn:btmh:1220{'ab' * 32}&xt=urn:btih:{IH.hex()}")
    assert m.infohash == IH


@pytest.mark.parametrize(
    ("uri", "text"),
    [
        (f"magnet:?xt=urn:btmh:1220{'ab' * 32}", "v2"),
        ("magnet:?dn=x", "xt"),
        ("magnet:", "xt"),
        ("magnet:?xt=urn:btih:abc", "infohash"),
        ("magnet:?xt=urn:btih:" + "z" * 40, "infohash"),
        ("magnet:?xt=urn:btih:" + "1" * 32, "infohash"),  # base32 has no digit 1
        ("magnet:?xt=urn:sha1:" + "a" * 40, "xt"),
        ("not a magnet", "magnet"),
    ],
)
def test_bad_magnets_are_usage_errors(uri: str, text: str) -> None:
    with pytest.raises(UsageError, match=text):
        parse_magnet(uri)


def test_tracker_list_is_capped_and_junk_trackers_are_dropped() -> None:
    trs = "".join(f"&tr=http%3A%2F%2Fh{i}%2Fa" for i in range(500))
    m = parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}{trs}&tr=ftp%3A%2F%2Fx&tr=" + "a" * 5000)
    assert len(m.trackers) == 64
    assert all(t.startswith("http://") for t in m.trackers)


def test_error_messages_do_not_echo_the_link() -> None:
    with pytest.raises(UsageError) as e:
        parse_magnet("magnet:?xt=urn:btih:abc&tr=http://h/announce?passkey=SECRET")
    assert "SECRET" not in str(e.value)


def test_a_plus_in_a_tracker_url_survives_but_is_a_space_in_the_name() -> None:
    m = parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}&dn=My+Movie&tr=http%3A%2F%2Fh%2Fannounce%2Bx%3Fa%3Db+c")
    assert m.trackers == ["http://h/announce+x?a=b+c"]
    assert m.name == "My Movie"


def test_the_name_shown_for_a_magnet_has_no_bidi_or_c1_controls() -> None:
    m = parse_magnet(f"magnet:?xt=urn:btih:{IH.hex()}&dn=film%E2%80%AEgpj.exe%C2%9B")
    assert m.name == "filmgpj.exe"


def test_the_scheme_is_matched_in_any_case() -> None:
    assert parse_magnet(f"MAGNET:?xt=urn:btih:{IH.hex()}").infohash == IH
