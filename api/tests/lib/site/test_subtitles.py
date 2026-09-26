import httpx
import pytest
from src.core.error import Error
from src.core.type import Code
from src.lib.site.filename import subtitle_filename
from src.lib.site.subtitles import SiteSubtitle, fetch_subtitle, same_subtitle, site_subtitles

HEADERS = {"User-Agent": "UA"}


def _entries(language: str, *exts: str, name: str | None = None) -> list[dict]:
    return [
        {"ext": ext, "url": f"https://www.youtube.com/api/timedtext?lang={language}&fmt={ext}", "name": name or language}
        for ext in exts
    ]


#: Shaped like yt-dlp's YouTube output: its own English and Spanish, a live
#: chat replay, and captions heard in English and machine-translated into
#: everything else.
YOUTUBE = {
    "language": "en",
    "formats": [{"format_id": "18", "url": "https://media.test/18", "http_headers": HEADERS}],
    "subtitles": {
        "en": _entries("en", "json3", "srv3", "vtt", "ttml", name="English"),
        "es": _entries("es", "srt", "json3", name="Spanish"),
        "de": _entries("de", "json3", "ttml", name="German"),
        "live_chat": [{"ext": "json", "url": "https://www.youtube.com/live_chat"}],
    },
    "automatic_captions": {
        "en-orig": _entries("en", "json3", "vtt", name="English (Original)"),
        "en": _entries("en", "vtt", name="English"),
        "fr": _entries("fr", "vtt", name="French"),
        "ja": _entries("ja", "vtt", name="Japanese"),
    },
}


def test_a_page_s_own_subtitles_come_first_in_a_format_ffmpeg_reads() -> None:
    found = site_subtitles(YOUTUBE)

    assert [(s.language, s.name, s.automatic, s.ext) for s in found] == [
        ("en", "English", False, "vtt"),
        ("es", "Spanish", False, "srt"),
        # German comes only as json3 and TTML: skipped.
        # Captions only in the language it was spoken in, not the translations.
        ("en", "English (Original) (auto-generated)", True, "vtt"),
    ]
    assert found[0].url.endswith("fmt=vtt")
    assert found[0].headers == HEADERS


def test_without_an_orig_key_the_video_s_language_picks_the_captions() -> None:
    info = {"language": "fr", "automatic_captions": {"fr": _entries("fr", "vtt"), "en": _entries("en", "vtt")}}

    assert [(s.language, s.automatic) for s in site_subtitles(info)] == [("fr", True)]


def test_without_either_there_are_no_captions() -> None:
    info = {"automatic_captions": {"fr": _entries("fr", "vtt"), "en": _entries("en", "vtt")}}
    assert site_subtitles(info) == []


def test_a_caption_s_own_label_that_says_auto_isn_t_said_twice() -> None:
    info = {"language": "en", "automatic_captions": {"en": _entries("en", "vtt", name="English (auto-generated)")}}
    assert site_subtitles(info)[0].name == "English (auto-generated)"


def test_a_page_with_none_has_none() -> None:
    assert site_subtitles({"formats": []}) == []


def test_the_same_track_across_two_extractions() -> None:
    a = SiteSubtitle("en", "English", True, "vtt", "https://a")
    assert same_subtitle(a, SiteSubtitle("en", "", True, "", "https://b"))
    assert not same_subtitle(a, SiteSubtitle("en", "", False, "", "https://a"))


@pytest.mark.asyncio
async def test_a_subtitle_file_is_fetched_with_the_site_s_headers() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=b"WEBVTT\n")

    raw = await fetch_subtitle("https://site.test/s.vtt", HEADERS, transport=httpx.MockTransport(handler))

    assert raw == b"WEBVTT\n"
    assert seen[0].headers["User-Agent"] == "UA"


@pytest.mark.asyncio
@pytest.mark.parametrize(("status", "code", "retry_able"), [(403, Code.FORBIDDEN, False), (500, Code.BAD_GATEWAY, True)])
async def test_an_expired_url_is_a_403_and_anything_else_a_502(status: int, code: Code, retry_able: bool) -> None:
    transport = httpx.MockTransport(lambda _request: httpx.Response(status))

    with pytest.raises(Error) as caught:
        await fetch_subtitle("https://site.test/s.vtt", {}, transport=transport)
    assert (caught.value.code, caught.value.retry_able) == (code, retry_able)


@pytest.mark.parametrize(
    ("language", "automatic", "ext", "name"),
    [
        ("en", False, "vtt", "My_Video_abc.en.vtt"),
        ("pt-BR", False, "srt", "My_Video_abc.pt-BR.srt"),
        ("en", True, "vtt", "My_Video_abc.en.auto.vtt"),
        ("../x", False, "vtt", "My_Video_abc.x.vtt"),
        ("", False, "vtt", "My_Video_abc.und.vtt"),
    ],
)
def test_saved_subtitles_are_named_after_the_video(language: str, automatic: bool, ext: str, name: str) -> None:
    assert subtitle_filename("My_Video_abc.mp4", language, ext, automatic=automatic) == name
