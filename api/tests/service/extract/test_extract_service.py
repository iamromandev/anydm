"""What ``POST /extract`` says about a link from any site."""

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.schema.extract import ExtractSchema, PlaylistSchema
from src.data.type import Preset
from src.lib.site import error as site_error
from src.lib.site.client import PlaylistInfo, Tab
from src.service.extract.extract_service import ExtractService, playlist_url

from tests.sites import FakeSiteClient, site_info


def _service(site: str, **overrides: object) -> ExtractService:
    return ExtractService(client=FakeSiteClient(site_info(site, **overrides)))


@pytest.mark.asyncio
async def test_extract_describes_the_page() -> None:
    data = await _service("vimeo").extract("http://vimeo.com/75629013")

    assert isinstance(data, ExtractSchema)
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

    assert isinstance(data, ExtractSchema)
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
    assert caught.value.type == ErrorType.LIVE_NOT_SUPPORTED


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


PLAYLIST = PlaylistInfo(
    extractor="YoutubeTab",
    id="PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC",
    title="29C3: Not my department",
    uploader="Christiaan008",
    thumbnail="https://img.test/pl.jpg",
    webpage_url="https://www.youtube.com/playlist?list=PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC",
    count=96,
)


def _listing_service(playlist: PlaylistInfo) -> ExtractService:
    return ExtractService(client=FakeSiteClient(site_info("youtube"), playlist=playlist))


@pytest.mark.asyncio
async def test_a_video_answers_media() -> None:
    data = await _service("vimeo").extract("http://vimeo.com/75629013")

    assert isinstance(data, ExtractSchema)
    assert data.type == "media"
    assert data.playlist_url is None


@pytest.mark.asyncio
async def test_a_playlist_answers_its_header_and_every_preset() -> None:
    data = await _listing_service(PLAYLIST).extract(PLAYLIST.webpage_url)

    assert isinstance(data, PlaylistSchema)
    assert (data.type, data.id, data.title, data.count) == ("playlist", PLAYLIST.id, PLAYLIST.title, 96)
    assert (data.uploader, data.thumbnail, data.webpage_url) == (
        "Christiaan008",
        PLAYLIST.thumbnail,
        PLAYLIST.webpage_url,
    )
    assert data.presets == list(Preset)
    assert data.tabs == []


@pytest.mark.asyncio
async def test_a_channel_answers_its_tabs() -> None:
    channel = PlaylistInfo(
        extractor="YoutubeTab",
        id="@3blue1brown",
        title="3Blue1Brown",
        tabs=[Tab("Videos", "https://www.youtube.com/@3blue1brown/videos")],
    )

    data = await _listing_service(channel).extract("https://www.youtube.com/@3blue1brown")

    assert isinstance(data, PlaylistSchema)
    assert data.type == "channel"
    assert [(t.name, t.url) for t in data.tabs] == [("Videos", "https://www.youtube.com/@3blue1brown/videos")]


@pytest.mark.asyncio
async def test_a_watch_link_naming_a_playlist_offers_the_whole_list() -> None:
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC&index=3"

    data = await _service("youtube").extract(url)

    assert isinstance(data, ExtractSchema)
    assert data.playlist_url == "https://www.youtube.com/playlist?list=PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC"


def test_a_mix_or_another_site_offers_no_playlist() -> None:
    assert playlist_url("https://www.youtube.com/watch?v=x&list=RDx", "Youtube") is None
    assert playlist_url("https://vimeo.com/1?list=PL1", "Vimeo") is None
    assert playlist_url("https://www.youtube.com/watch?v=x", "Youtube") is None
