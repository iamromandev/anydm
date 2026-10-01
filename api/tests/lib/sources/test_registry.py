"""The built-in sources' constants, the kinds a source can be, and the check every address goes through."""

import pytest
from src.lib.sources.registry import (
    BUILTINS,
    KINDS,
    default_url,
    make_source,
    valid_name,
    validate_base_url,
)
from src.lib.sources.registry import (
    test_query_for as query_for,
)
from src.lib.sources.torznab_source import TorznabSource
from src.lib.torznab.torznab import Indexer


def test_every_builtin_declares_its_constants() -> None:
    assert list(BUILTINS) == ["apibay", "nyaa", "eztv"]
    for builtin in BUILTINS.values():
        assert builtin.default_enabled is True
        assert validate_base_url(builtin.default_url) == builtin.default_url
        assert builtin.make(builtin.default_url).name == builtin.name


def test_every_kind_resolves_a_default_a_source_and_a_test_query() -> None:
    assert KINDS == ("torznab", "apibay", "nyaa", "eztv")
    assert default_url("torznab") is None
    for kind in ("apibay", "nyaa", "eztv"):
        url = default_url(kind)
        assert url == BUILTINS[kind].default_url
        # A built-in parser keeps its own name; the row's name is the person's label for it.
        assert make_source(kind, f"{kind}-lan", url, None).name == kind
    assert query_for("apibay") == "ubuntu"
    assert query_for("torznab") == "ubuntu"
    assert query_for("nyaa") == "" and query_for("eztv") == ""


def test_a_torznab_source_is_built_with_its_name_address_and_key() -> None:
    source = make_source("torznab", "prowlarr", "http://p.test/1/api", "key-1")

    assert isinstance(source, TorznabSource)
    assert source.indexer == Indexer("prowlarr", "http://p.test/1/api", "key-1")


def test_an_unknown_kind_builds_nothing() -> None:
    with pytest.raises(ValueError, match="unknown source kind"):
        make_source("rss", "r", "http://r.test", None)


@pytest.mark.parametrize("name", ["prowlarr-1", "nyaa", "a"])
def test_a_good_name_is_lowercase_letters_digits_and_dashes(name: str) -> None:
    assert valid_name(name) is True


@pytest.mark.parametrize("name", ["", "Prowlarr", "a b", "a_b", "a.b", "a" * 65])
def test_a_bad_name_is_refused(name: str) -> None:
    assert valid_name(name) is False


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("https://apibay.org", "https://apibay.org"),
        ("  https://apibay.org/  ", "https://apibay.org"),
        ("http://192.168.1.10:8080/x//", "http://192.168.1.10:8080/x"),
    ],
)
def test_an_address_is_trimmed_and_loses_its_trailing_slashes(text: str, expected: str) -> None:
    assert validate_base_url(text) == expected


@pytest.mark.parametrize("text", ["", "   ", "apibay.org", "ftp://x.test", "https://", "https://a b.test", "http://x.test:notaport"])
def test_a_bad_address_is_refused_in_words(text: str) -> None:
    with pytest.raises(ValueError, match="address"):
        validate_base_url(text)
