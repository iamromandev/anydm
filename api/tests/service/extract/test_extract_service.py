"""What ``POST /extract`` says about a link from any site."""

import pytest
from src.core.error import Error
from src.core.type import Code
from src.data.type import Preset
from src.lib.site import error as site_error
from src.service.extract.extract_service import ExtractService

from tests.sites import FakeSiteClient, site_info


def _service(site: str, **overrides: object) -> ExtractService:
    return ExtractService(client=FakeSiteClient(site_info(site, **overrides)))


@pytest.mark.asyncio
async def test_extract_describes_the_page() -> None:
    data = await _service("vimeo").extract("http://vimeo.com/75629013")

    assert (data.extractor, data.id) == ("Vimeo", "75629013")
    assert data.title == site_info("vimeo").title
    assert (data.uploader, data.duration) == ("Someone", 187)
    assert data.thumbnail == "https://img.test/vimeo.jpg"
    assert data.webpage_url == site_info("vimeo").webpage_url


@pytest.mark.asyncio
async def test_extract_offers_every_preset_the_formats_can_serve() -> None:
    data = await _service("vimeo").extract("http://vimeo.com/75629013")

    # Vimeo's separate audio exists only as HLS, which the fragment path fetches.
    assert data.presets == [Preset.BEST, Preset.P1080, Preset.P720, Preset.P480, Preset.MP3]


@pytest.mark.asyncio
async def test_formats_have_string_ids_and_leave_storyboards_out() -> None:
    data = await _service("youtube").extract("https://youtu.be/dQw4w9WgXcQ")

    by_id = {f.id: f for f in data.formats}
    assert "sb0" not in by_id
    assert (by_id["137"].height, by_id["137"].has_video, by_id["137"].has_audio) == (1080, True, False)
    assert (by_id["137"].size, by_id["137"].fragmented) == (80_911_999, False)
    assert by_id["233"].fragmented is True


@pytest.mark.asyncio
async def test_an_audio_site_offers_mp3() -> None:
    data = await _service("soundcloud").extract("http://soundcloud.com/x/y")

    assert data.presets == [Preset.MP3]


@pytest.mark.asyncio
async def test_a_streaming_only_site_offers_its_presets() -> None:
    data = await _service("dailymotion").extract("https://dailymotion.com/video/x")

    assert data.presets == [Preset.BEST, Preset.P1080, Preset.P720, Preset.P480]


@pytest.mark.asyncio
async def test_a_live_stream_is_refused() -> None:
    with pytest.raises(Error) as caught:
        await _service("twitch", is_live=True).extract("https://twitch.tv/x")

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


@pytest.mark.asyncio
async def test_an_unsupported_link_says_so() -> None:
    service = ExtractService(
        client=FakeSiteClient(site_info("youtube"), fail=site_error.unsupported_url("https://example.test/f.zip"))
    )

    with pytest.raises(Error) as caught:
        await service.extract("https://example.test/f.zip")

    assert (caught.value.code, caught.value.type.value if caught.value.type else None) == (
        Code.BAD_REQUEST,
        "unsupported_url",
    )
