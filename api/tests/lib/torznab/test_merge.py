"""The same torrent from several indexers becomes one result, and only indexers' own links are fetched."""

from dataclasses import replace

import pytest
from src.lib.torznab.torznab import Indexer, Result, link_allowed, merge


def _result(title: str, **fields: object) -> Result:
    base = Result(title, 100, None, None, None, "other", None, None, "http://p/dl", ("p",))
    return replace(base, **fields)  # type: ignore[arg-type]


def test_one_torrent_from_two_indexers_is_one_result_with_the_best_numbers() -> None:
    prowlarr = [_result("Bunny", info_hash="aa", seeders=120, leechers=15, link="http://p/dl/1", indexers=("p",))]
    jackett = [_result("Bunny", info_hash="aa", seeders=150, leechers=10, magnet="magnet:?xt=urn:btih:aa", link=None, indexers=("j",))]

    (bunny,) = merge([prowlarr, jackett], limit=10)

    assert (bunny.seeders, bunny.leechers) == (150, 15)
    assert bunny.magnet == "magnet:?xt=urn:btih:aa"
    assert bunny.link == "http://p/dl/1"
    assert bunny.indexers == ("p", "j")


def test_without_a_hash_the_title_and_size_decide() -> None:
    merged = merge([[_result("Same Name", size=1)], [_result("same name", size=1, indexers=("j",))], [_result("Same Name", size=2)]], limit=10)

    assert [(r.size, r.indexers) for r in merged] == [(2, ("p",)), (1, ("p", "j"))]


def test_most_seeded_first_unknown_last_then_largest_and_the_limit_holds() -> None:
    rows = [
        _result("a", info_hash="a", seeders=None, size=9),
        _result("b", info_hash="b", seeders=5, size=1),
        _result("c", info_hash="c", seeders=5, size=3),
        _result("d", info_hash="d", seeders=40, size=1),
    ]

    assert [r.title for r in merge([rows], limit=10)] == ["d", "c", "b", "a"]
    assert [r.title for r in merge([rows], limit=2)] == ["d", "c"]


INDEXERS = [Indexer("p", "http://prowlarr:9696/1/api"), Indexer("j", "https://jackett.test/api")]


@pytest.mark.parametrize(
    "link",
    [
        "http://prowlarr:9696/1/download?link=x",
        "http://PROWLARR:9696/other/path",
        "https://jackett.test:443/dl/x",
    ],
)
def test_links_from_an_indexers_own_origin_may_be_fetched(link: str) -> None:
    assert link_allowed(link, INDEXERS)


@pytest.mark.parametrize(
    "link",
    [
        "http://prowlarr:9697/1/download",
        "https://prowlarr:9696/1/download",
        "http://evil.test/x",
        "http://prowlarr:9696@evil.test/x",
        "http://user:pw@prowlarr:9696/x",
        "file:///etc/passwd",
        "http://prowlarr:notaport/x",
        "magnet:?xt=urn:btih:aa",
    ],
)
def test_anything_else_is_refused(link: str) -> None:
    assert not link_allowed(link, INDEXERS)
