"""The normal form a URL is stored and compared by, shared by the Url table and a direct link's ref."""

from src.core.url import normalize_url, url_hash
from src.lib.identity import url_ref


def test_case_folds_where_it_does_not_matter() -> None:
    assert normalize_url("HTTPS://Example.COM/A.bin") == "https://example.com/A.bin"


def test_the_fragment_and_tracking_parameters_go_and_the_query_is_sorted() -> None:
    assert normalize_url("https://e.test/a?b=2&utm_source=x&a=1&fbclid=y#top") == "https://e.test/a?a=1&b=2"


def test_the_hash_is_a_fixed_size_hex_digest() -> None:
    digest = url_hash(normalize_url("https://e.test/a"))
    assert len(digest) == 64
    assert set(digest) <= set("0123456789abcdef")


def test_a_direct_links_ref_is_its_urls_hash() -> None:
    # One hash for both, so a Url row and the download it serves agree on what the address is.
    address = "HTTPS://Example.com/a.bin?utm_medium=mail#x"
    assert url_ref(address) == url_hash(normalize_url(address))
