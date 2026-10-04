"""A direct link's identity: the same file by another spelling of its address is the same download."""

from src.lib.identity import HTTP_PROVIDER, TORRENT_PROVIDER, url_ref


def test_the_built_in_providers_are_lowercase_constants() -> None:
    assert (HTTP_PROVIDER, TORRENT_PROVIDER) == ("http", "torrent")


def test_a_ref_is_a_fixed_size_hex_hash() -> None:
    ref = url_ref("https://example.com/a.bin")

    assert len(ref) == 64
    assert set(ref) <= set("0123456789abcdef")


def test_the_same_address_has_the_same_ref() -> None:
    assert url_ref("https://example.com/a.bin") == url_ref("https://example.com/a.bin")


def test_the_scheme_and_host_ignore_case() -> None:
    assert url_ref("HTTPS://Example.COM/a.bin") == url_ref("https://example.com/a.bin")


def test_the_path_keeps_its_case() -> None:
    assert url_ref("https://example.com/A.bin") != url_ref("https://example.com/a.bin")


def test_a_fragment_is_not_part_of_the_address() -> None:
    assert url_ref("https://example.com/a.bin#top") == url_ref("https://example.com/a.bin")


def test_tracking_parameters_are_dropped() -> None:
    plain = url_ref("https://example.com/a.bin?id=7")

    assert url_ref("https://example.com/a.bin?id=7&utm_source=x&utm_medium=y") == plain
    assert url_ref("https://example.com/a.bin?utm_campaign=z&id=7") == plain


def test_other_parameters_count_and_their_order_does_not() -> None:
    assert url_ref("https://example.com/a?x=1&y=2") == url_ref("https://example.com/a?y=2&x=1")
    assert url_ref("https://example.com/a?x=1") != url_ref("https://example.com/a?x=2")


def test_a_different_file_is_a_different_ref() -> None:
    assert url_ref("https://example.com/a.bin") != url_ref("https://example.com/b.bin")
