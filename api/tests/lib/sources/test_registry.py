"""SEARCH_SOURCES and SEARCH_SOURCE_URLS: the built-ins by name, and mistakes named at startup."""

import pytest
from src.lib.sources.apibay import Apibay
from src.lib.sources.nyaa import Nyaa
from src.lib.sources.registry import DEFAULT_SOURCES, parse_sources


def test_the_default_runs_the_shipped_sources_at_their_own_urls() -> None:
    sources = parse_sources(DEFAULT_SOURCES, "")

    assert sources == [Apibay(base="https://apibay.org"), Nyaa(base="https://nyaa.si")]


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
