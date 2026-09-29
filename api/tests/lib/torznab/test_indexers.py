"""Indexers named in .env, the URLs a search asks them, and URLs made safe to log."""

from urllib.parse import parse_qs, urlsplit

import pytest
from src.lib.torznab.torznab import Indexer, build_url, parse_indexers, redact


def test_indexers_pair_urls_with_their_keys() -> None:
    indexers = parse_indexers(
        "prowlarr-1=http://prowlarr:9696/1/api, jackett-all=http://jackett:9117/api/v2.0/indexers/all/results/torznab",
        "prowlarr-1=abc",
    )

    assert indexers == [
        Indexer("prowlarr-1", "http://prowlarr:9696/1/api", "abc"),
        Indexer("jackett-all", "http://jackett:9117/api/v2.0/indexers/all/results/torznab", None),
    ]
    assert indexers[0].origin == ("http", "prowlarr", 9696)
    assert Indexer("x", "https://idx.test/api").origin == ("https", "idx.test", 443)


def test_nothing_configured_is_no_indexers() -> None:
    assert parse_indexers("", "") == []
    assert parse_indexers(" , ", "") == []


@pytest.mark.parametrize(
    ("urls", "keys", "problem"),
    [
        ("prowlarr", "", "is not name=value"),
        ("Prowlarr=http://p/api", "", "is not name=value"),
        ("p=http://a/api,p=http://b/api", "", "p appears twice"),
        ("p=ftp://a/api", "", "p's URL must be http or https"),
        ("p=http://a/api", "q=key", "names q, which SEARCH_INDEXERS doesn't"),
    ],
)
def test_a_bad_setting_says_what_is_wrong(urls: str, keys: str, problem: str) -> None:
    with pytest.raises(ValueError, match=problem):
        parse_indexers(urls, keys)


def test_a_search_url_keeps_the_indexers_own_query_and_adds_the_search() -> None:
    url = build_url(Indexer("p", "http://p:9696/1/api?extra=1", "abc"), "big buck bunny", "movies")
    query = parse_qs(urlsplit(url).query)

    assert url.startswith("http://p:9696/1/api?")
    assert query == {
        "extra": ["1"],
        "t": ["search"],
        "q": ["big buck bunny"],
        "cat": ["2000"],
        "limit": ["100"],
        "apikey": ["abc"],
    }


def test_an_empty_query_asks_for_the_latest_and_sends_no_q() -> None:
    url = build_url(Indexer("p", "http://p:9696/1/api", "abc"), "", "tv")
    query = parse_qs(urlsplit(url).query, keep_blank_values=True)

    assert query == {"t": ["search"], "cat": ["5000"], "limit": ["100"], "apikey": ["abc"]}


def test_all_categories_and_no_key_send_neither() -> None:
    query = parse_qs(urlsplit(build_url(Indexer("p", "http://p/api"), "x", "all")).query)

    assert "cat" not in query
    assert "apikey" not in query


def test_redact_hides_the_key_and_nothing_else() -> None:
    assert redact("http://p/api?t=search&apikey=abc&q=x") == "http://p/api?t=search&apikey=%2A%2A%2A&q=x"
    assert redact("http://p/api?t=search") == "http://p/api?t=search"
