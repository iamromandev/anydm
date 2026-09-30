import time
from collections.abc import Callable

import pytest
from src.core.error import Error
from src.lib.site import error as site_error
from src.lib.site.client import VideoHit
from src.service.search.video_service import VIDEO_TTL_S, VideoSearchService

from tests.sites import FakeSiteClient, site_info


def _hit(n: int = 1, **over: object) -> VideoHit:
    fields: dict[str, object] = {
        "id": f"id{n}",
        "url": f"https://www.youtube.com/watch?v=id{n}",
        "title": f"Video {n}",
        "channel": "Blender",
        "duration": 596,
        "thumbnail": "https://i.ytimg.com/t.jpg",
        "views": 1200,
        "timestamp": 1_700_000_000,
    }
    fields.update(over)
    return VideoHit(**fields)  # type: ignore[arg-type]


def _service(fake: FakeSiteClient, clock: Callable[[], float] = time.monotonic) -> VideoSearchService:
    return VideoSearchService(fake, clock=clock)


@pytest.mark.asyncio
async def test_it_maps_a_hit_to_the_answer() -> None:
    fake = FakeSiteClient(site_info("youtube"), hits=[_hit()])

    answer = await _service(fake).search("bunny", 20)

    video = answer.results[0]
    assert (video.title, video.url, video.channel, video.duration, video.views) == (
        "Video 1",
        "https://www.youtube.com/watch?v=id1",
        "Blender",
        596,
        1200,
    )
    assert video.published == "2023-11-14T22:13:20Z"
    assert fake.searched == [("bunny", 20)]


@pytest.mark.asyncio
async def test_a_hit_with_no_date_has_no_published() -> None:
    fake = FakeSiteClient(site_info("youtube"), hits=[_hit(timestamp=None)])

    assert (await _service(fake).search("x", 5)).results[0].published is None


@pytest.mark.asyncio
async def test_the_same_question_is_answered_from_the_cache_for_a_minute() -> None:
    now = [1000.0]
    fake = FakeSiteClient(site_info("youtube"), hits=[_hit()])
    service = _service(fake, clock=lambda: now[0])

    await service.search("bunny", 20)
    await service.search("bunny", 20)
    assert len(fake.searched) == 1

    now[0] += VIDEO_TTL_S + 1
    await service.search("bunny", 20)
    assert len(fake.searched) == 2


@pytest.mark.asyncio
async def test_another_query_or_limit_is_asked_again() -> None:
    fake = FakeSiteClient(site_info("youtube"), hits=[_hit()])
    service = _service(fake)

    await service.search("bunny", 20)
    await service.search("bunny", 10)
    await service.search("sintel", 20)

    assert len(fake.searched) == 3


@pytest.mark.asyncio
async def test_a_failure_is_raised_and_not_cached() -> None:
    fake = FakeSiteClient(site_info("youtube"), search_fail=site_error.extraction_failed("HTTP Error 429"))
    service = _service(fake)

    with pytest.raises(Error):
        await service.search("bunny", 20)
    with pytest.raises(Error):
        await service.search("bunny", 20)

    assert len(fake.searched) == 2


@pytest.mark.asyncio
async def test_a_search_that_never_answers_is_a_failure() -> None:
    class Slow(FakeSiteClient):
        def search(self, query: str, *, limit: int) -> list[VideoHit]:
            time.sleep(0.5)
            return []

    service = VideoSearchService(Slow(site_info("youtube")), timeout_s=0.05)

    with pytest.raises(Error):
        await service.search("bunny", 20)
