"""SEARCH_SOURCES and SEARCH_SOURCE_URLS: the built-ins by name, and mistakes named at startup."""

import pytest
from src.lib.sources.apibay import Apibay
from src.lib.sources.eztv import Eztv
from src.lib.sources.nyaa import Nyaa
from src.lib.sources.registry import BUILTINS, DEFAULT_SOURCES, parse_sources, validate_base_url


def test_every_builtin_declares_its_constants() -> None:
    assert list(BUILTINS) == ["apibay", "nyaa", "eztv"]
    for builtin in BUILTINS.values():
        assert builtin.label and builtin.default_enabled is True
        assert validate_base_url(builtin.default_url) == builtin.default_url
        assert builtin.make(builtin.default_url).name == builtin.name
    assert BUILTINS["apibay"].label == "apibay"
    assert BUILTINS["nyaa"].label == "Nyaa"
    assert BUILTINS["eztv"].label == "EZTV"


def test_only_apibay_needs_a_query_to_test() -> None:
    assert BUILTINS["apibay"].test_query == "ubuntu"
    assert BUILTINS["nyaa"].test_query == ""
    assert BUILTINS["eztv"].test_query == ""


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


def test_the_default_runs_the_shipped_sources_at_their_own_urls() -> None:
    sources = parse_sources(DEFAULT_SOURCES, "")

    assert sources == [Apibay(base="https://apibay.org"), Nyaa(base="https://nyaa.si"), Eztv(base="https://eztvx.to")]


def test_empty_turns_the_built_ins_off() -> None:
    assert parse_sources("", "") == []
    assert parse_sources(" , ", "") == []


def test_a_url_override_replaces_a_base_url() -> None:
    assert parse_sources("apibay", "apibay=https://mirror.test") == [Apibay(base="https://mirror.test")]


@pytest.mark.parametrize(
    ("names", "urls", "taken", "message"),
    [
        ("nope", "", (), "SEARCH_SOURCES: nope isn't a built-in source"),
        ("apibay,apibay", "", (), "SEARCH_SOURCES: apibay appears twice"),
        ("apibay", "", ("apibay",), "SEARCH_SOURCES: apibay is also a SEARCH_INDEXERS name"),
        ("apibay", "nyaa=https://x.test", (), "SEARCH_SOURCE_URLS names nyaa, which SEARCH_SOURCES doesn't run"),
        ("apibay", "apibay=ftp://x.test", (), "SEARCH_SOURCE_URLS: apibay's URL must be http or https"),
        ("apibay", "apibay", (), "SEARCH_SOURCE_URLS"),
    ],
)
def test_a_mistake_stops_startup_naming_it(names: str, urls: str, taken: tuple[str, ...], message: str) -> None:
    with pytest.raises(ValueError, match=message):
        parse_sources(names, urls, taken)
