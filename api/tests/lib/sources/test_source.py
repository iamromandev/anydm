"""What every source shares: magnets from a hash, tolerant numbers and dates, and asking over HTTP."""

from datetime import UTC, datetime

import httpx
import pytest
from src.lib.sources.source import TRACKERS, SourceError, get, magnet_for, to_count, to_date


def test_a_magnet_from_a_hash_lowercases_it_and_encodes_the_name_and_trackers() -> None:
    magnet = magnet_for("AABBCC", "Big Buck: Bunny & Co")

    assert magnet.startswith("magnet:?xt=urn:btih:aabbcc&dn=Big%20Buck%3A%20Bunny%20%26%20Co&tr=")
    assert "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" in magnet
    assert magnet.count("&tr=") == len(TRACKERS)


@pytest.mark.parametrize(
    ("value", "expected"),
    [("12", 12), (12, 12), ("3.0", 3), (0, 0), ("", None), (None, None), ("-1", None), ("x", None), (True, None)],
)
def test_counts_read_strings_and_integers_and_refuse_the_rest(value: str | int | None, expected: int | None) -> None:
    assert to_count(value) == expected


def test_dates_are_rfc_2822_or_nothing() -> None:
    assert to_date("Mon, 28 Sep 2026 10:00:00 +0000") == datetime(2026, 9, 28, 10, 0, tzinfo=UTC)
    assert to_date("not a date") is None
    assert to_date(None) is None


def test_a_date_with_no_zone_is_utc_not_the_servers_local_time() -> None:
    # "-0000" is RFC 2822 for UTC with no local offset; Python parses it as a naive datetime,
    # which would sort as local time and push Nyaa's posts hours down a newest-first list.
    assert to_date("Sat, 12 Sep 2026 15:45:00 -0000") == datetime(2026, 9, 12, 15, 45, tzinfo=UTC)


def _client(status: int) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(status, content=b"ok")))


@pytest.mark.asyncio
async def test_get_returns_a_good_answer() -> None:
    assert (await get(_client(200), "http://x.test/a", 1)).content == b"ok"


@pytest.mark.asyncio
@pytest.mark.parametrize(("status", "message"), [(401, "custom refusal"), (403, "custom refusal"), (503, "answered 503"), (404, "answered 404")])
async def test_get_says_in_words_why_an_answer_is_bad(status: int, message: str) -> None:
    with pytest.raises(SourceError) as caught:
        await get(_client(status), "http://x.test/a", 1, refused="custom refusal")

    assert caught.value.message == message
