"""Torznab answers from Prowlarr and Jackett, read into results."""

from datetime import UTC, datetime
from pathlib import Path

import pytest
from src.lib.torznab.torznab import Result, TorznabError, parse

FIXTURES = Path(__file__).parents[2] / "fixtures" / "torznab"


def _read(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def test_prowlarr_gives_the_magnet_from_its_attribute_and_keeps_the_link() -> None:
    bunny, flac = parse(_read("prowlarr.xml"), "prowlarr-1")

    assert bunny == Result(
        title="Big Buck Bunny 1080p",
        size=725614592,
        seeders=120,
        leechers=15,
        published=datetime(2026, 9, 28, 10, 0, tzinfo=UTC),
        category="movies",
        info_hash="dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c",
        magnet="magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny",
        link="http://prowlarr:9696/1/download?apikey=abc&link=xyz",
        indexers=("prowlarr-1",),
    )
    # No magnet: the .torrent link is what Add fetches. A bad date is dropped, not fatal.
    assert (flac.magnet, flac.link, flac.size, flac.seeders, flac.published, flac.category) == (
        None,
        "http://prowlarr:9696/1/download?apikey=abc&link=abc",
        31457280,
        None,
        None,
        "music",
    )


def test_jackett_gives_the_magnet_from_its_enclosure_and_the_hash_from_the_magnet() -> None:
    bunny, debian = parse(_read("jackett.xml"), "jackett-all")

    assert bunny.magnet is not None and bunny.magnet.startswith("magnet:?xt=urn:btih:DD82")
    assert bunny.link is None
    assert bunny.info_hash == "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    assert (bunny.seeders, bunny.leechers, bunny.category) == (150, 10, "movies")
    # A size that isn't a number and a negative seeder count become unknown.
    assert (debian.size, debian.seeders, debian.category) == (None, None, "software")
    assert debian.link == "http://jackett:9117/dl/debian/?jackett_apikey=def&path=q"


def test_an_indexer_error_is_its_own_words() -> None:
    with pytest.raises(TorznabError) as caught:
        parse(_read("error.xml"), "p")
    assert caught.value.message == "Invalid API Key"


def test_something_that_isnt_xml_is_an_unreadable_answer() -> None:
    with pytest.raises(TorznabError) as caught:
        parse(b"<html><body>Cloudflare", "p")
    assert caught.value.message == "unreadable answer"
