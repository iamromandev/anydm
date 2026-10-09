"""A batch add: each link goes through the normal add, and every one is answered for."""

import asyncio
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code
from src.data.schema.transfer import BatchResult
from src.data.type import Preset
from src.lib.pattern import MAX_ITEMS
from src.lib.site import error as site_error

from tests.service.download.test_download_service import _service
from tests.sites import FakeSiteClient, site_info


class RoutingClient(FakeSiteClient):
    """``files.test`` is no site's page, ``gone.test`` is a page the site refuses, ``boom.test`` breaks."""

    def __init__(self) -> None:
        super().__init__(site_info("youtube"))
        self.running = 0
        self.most = 0

    async def extract(self, url: str) -> Any:
        self.extracted.append(url)
        self.running += 1
        self.most = max(self.most, self.running)
        try:
            await asyncio.sleep(0)
            if "files.test" in url:
                raise site_error.unsupported_url(url)
            if "gone.test" in url:
                raise Error.bad_request(message="Video unavailable")
            if "playlist.test" in url:
                raise site_error.playlist_not_supported()
            if "boom.test" in url:
                raise RuntimeError("the extractor fell over")
            return self.info
        finally:
            self.running -= 1


def _batch() -> Any:
    return _service(client=RoutingClient())


@pytest.mark.asyncio
async def test_each_link_is_added_as_the_add_box_would_and_answered_for() -> None:
    h = _batch()
    held = await h.service.enqueue_url("https://files.test/held.bin")

    results = await h.service.add_batch(
        [
            "https://youtu.be/dQw4w9WgXcQ",
            "https://files.test/a.bin",
            "https://files.test/held.bin",
            "https://gone.test/v",
            "magnet:?xt=urn:btih:abc",
        ],
        None,
        Preset.BEST,
    )

    assert [r.result for r in results] == [
        BatchResult.ADDED,
        BatchResult.ADDED,
        BatchResult.DUPLICATE,
        BatchResult.ERROR,
        BatchResult.ADDED,
    ]
    assert [r.url for r in results][2] == "https://files.test/held.bin"
    assert results[2].download_id == held.id
    assert results[2].message is not None and "Already in your list" in results[2].message
    assert results[3].message == "Video unavailable" and results[3].download_id is None
    # A page by its site, a file no site supports as a direct download, a magnet as a torrent.
    assert results[0].download_id is not None and results[1].download_id is not None
    assert h.torrents.enqueued == ["magnet:?xt=urn:btih:abc"]
    assert len(h.repo.created) == 3  # the held file, the page, the direct file


@pytest.mark.asyncio
async def test_a_link_repeated_in_the_batch_is_added_once_and_reported_as_a_duplicate() -> None:
    h = _batch()

    results = await h.service.add_batch(["https://files.test/a.bin"] * 3, None, Preset.BEST)

    assert [r.result for r in results] == [BatchResult.ADDED, BatchResult.DUPLICATE, BatchResult.DUPLICATE]
    assert results[1].message == "Repeated in this batch"
    assert results[1].download_id == results[0].download_id
    assert len(h.repo.created) == 1


@pytest.mark.asyncio
async def test_allow_duplicate_adds_every_copy() -> None:
    h = _batch()

    results = await h.service.add_batch(["https://files.test/a.bin"] * 2, None, Preset.BEST, allow_duplicate=True)

    assert [r.result for r in results] == [BatchResult.ADDED, BatchResult.ADDED]
    assert len(h.repo.created) == 2


@pytest.mark.asyncio
async def test_one_link_that_breaks_does_not_stop_the_others() -> None:
    h = _batch()

    results = await h.service.add_batch(
        ["https://files.test/a.bin", "https://boom.test/v", "https://files.test/b.bin"], None, Preset.BEST
    )

    assert [r.result for r in results] == [BatchResult.ADDED, BatchResult.ERROR, BatchResult.ADDED]
    assert results[1].message == "Could not add this link"


@pytest.mark.asyncio
async def test_a_pattern_is_expanded_and_added_in_order() -> None:
    h = _batch()

    results = await h.service.add_batch(None, "https://files.test/img[08-11].png", Preset.BEST)

    assert [r.url for r in results] == [f"https://files.test/img{n}.png" for n in ("08", "09", "10", "11")]
    assert {r.result for r in results} == {BatchResult.ADDED}


@pytest.mark.asyncio
async def test_a_playlist_link_is_an_error_that_says_to_choose_its_videos() -> None:
    h = _batch()

    results = await h.service.add_batch(["https://playlist.test/list?id=PL1"], None, Preset.BEST)

    assert results[0].result == BatchResult.ERROR
    assert results[0].message is not None and "choose its videos" in results[0].message
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_a_line_that_is_not_a_link_is_its_own_error() -> None:
    h = _batch()

    results = await h.service.add_batch(["ftp://files.test/a.bin"], None, Preset.BEST)

    assert results[0].result == BatchResult.ERROR and results[0].message


@pytest.mark.asyncio
async def test_blank_lines_are_dropped_and_lines_trimmed() -> None:
    h = _batch()

    results = await h.service.add_batch(["  https://files.test/a.bin  ", "", "   "], None, Preset.BEST)

    assert [r.url for r in results] == ["https://files.test/a.bin"]


@pytest.mark.asyncio
async def test_only_a_few_links_are_looked_at_at_once() -> None:
    h = _batch()

    await h.service.add_batch(None, "https://files.test/f[1-40]", Preset.BEST)

    assert 1 < h.client.most <= 4


def test_a_preview_names_the_links_and_adds_nothing() -> None:
    h = _batch()

    preview = h.service.preview_batch(None, "https://files.test/img[001-120].png")

    assert preview.count == 120 and len(preview.urls) == 120 and preview.urls[0] == "https://files.test/img001.png"
    assert h.repo.created == [] and h.client.extracted == []


@pytest.mark.parametrize(
    ("lines", "pattern"),
    [
        (None, "f[9-1]"),
        (None, "f[1-1001]"),
        (None, " "),
        ([], None),
        (["  ", ""], None),
        (["https://x.test/a"] * (MAX_ITEMS + 1), None),
    ],
)
def test_a_batch_that_cannot_be_made_is_a_400(lines: list[str] | None, pattern: str | None) -> None:
    h = _batch()

    with pytest.raises(Error) as caught:
        h.service.preview_batch(lines, pattern)

    assert caught.value.code == Code.BAD_REQUEST


def test_a_batch_may_hold_exactly_the_limit() -> None:
    h = _batch()

    assert h.service.preview_batch(["https://x.test/a"] * MAX_ITEMS, None).count == MAX_ITEMS
