"""The yt-dlp adapter for any site, driven by #53's recordings.

The fixtures carry no URLs or header values, so the tests add them.
"""

import errno
import json
import time
from importlib import metadata
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.site.client import DownloadStopped, FormatProgress, Resolved, YtDlpClient, classify, ytdlp_version

FIXTURES = Path(__file__).parents[2] / "fixtures" / "ytdlp"
HEADERS = {"User-Agent": "Mozilla/5.0 (test)", "Accept": "*/*"}


def _info(site: str, **overrides: Any) -> dict[str, Any]:
    """An ``extract_info`` result rebuilt from a fixture."""
    fixture = json.loads((FIXTURES / f"{site}.json").read_text())
    info: dict[str, Any] = {
        "extractor_key": fixture["extractor"],
        "id": fixture["id"],
        "title": fixture["title"],
        "uploader": "Someone",
        "duration": fixture["duration"],
        "thumbnail": f"https://img.test/{site}.jpg",
        "webpage_url": fixture["webpage_url"],
        "is_live": False,
        "formats": [
            {**f, "url": f"https://media.test/{site}/{f['format_id']}", "http_headers": dict(HEADERS)}
            for f in fixture["formats"]
        ],
    }
    info.update(overrides)
    return info


def _client(info: dict[str, Any] | None = None, **kwargs: Any) -> YtDlpClient:
    return YtDlpClient(extract=lambda _url: info or _info("vimeo"), **kwargs)


# --- extract -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_extract_maps_the_metadata() -> None:
    info = await _client(_info("vimeo")).extract("http://vimeo.com/75629013")

    assert info.extractor == "Vimeo"
    assert info.id == "75629013"
    assert (info.title, info.uploader) == (_info("vimeo")["title"], "Someone")
    assert info.duration == 187
    assert info.thumbnail == "https://img.test/vimeo.jpg"
    assert info.webpage_url == _info("vimeo")["webpage_url"]
    assert info.is_live is False


@pytest.mark.asyncio
async def test_extract_keeps_every_format_for_selection_to_judge() -> None:
    info = await _client(_info("youtube")).extract("https://youtu.be/dQw4w9WgXcQ")

    ids = {f.id for f in info.formats}
    assert {"137", "140", "233", "sb0"} <= ids
    assert len(info.formats) == len(_info("youtube")["formats"])


@pytest.mark.asyncio
async def test_a_fractional_duration_is_whole_seconds() -> None:
    info = await _client(_info("soundcloud")).extract("http://soundcloud.com/x/y")

    assert info.duration == 143


@pytest.mark.asyncio
async def test_a_single_format_page_is_its_own_format() -> None:
    # Some extractors put the one format on the info dict itself, with no list.
    single = {
        "extractor_key": "Generic",
        "id": "clip",
        "title": "clip",
        "url": "https://media.test/clip.mp4",
        "format_id": "0",
        "protocol": "https",
        "ext": "mp4",
    }
    info = await _client(single).extract("https://example.test/clip")

    assert [f.id for f in info.formats] == ["0"]


@pytest.mark.asyncio
async def test_extract_reports_a_live_stream_as_live() -> None:
    info = await _client(_info("twitch", is_live=True)).extract("https://twitch.tv/x")

    assert info.is_live is True


@pytest.mark.asyncio
async def test_a_direct_file_link_is_not_a_site() -> None:
    # yt-dlp's generic extractor "extracts" any file link, a PDF included, as
    # one format of unknown codecs. That is a job for the direct download.
    direct = {"extractor_key": "Generic", "id": "dummy", "title": "dummy", "direct": True,
              "url": "https://files.test/dummy.pdf", "ext": "unknown_video"}

    with pytest.raises(Error) as caught:
        await _client(direct).extract("https://files.test/dummy.pdf")

    assert caught.value.type == ErrorType.UNSUPPORTED_URL


@pytest.mark.asyncio
async def test_a_playlist_link_is_refused() -> None:
    playlist = {"_type": "playlist", "id": "PL1", "title": "A list", "entries": [_info("youtube")]}

    with pytest.raises(Error) as caught:
        await _client(playlist).extract("https://www.youtube.com/playlist?list=PL1")

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- resolve -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_gives_each_part_its_url_and_headers_from_one_extraction() -> None:
    calls: list[str] = []

    def extract(url: str) -> dict[str, Any]:
        calls.append(url)
        return _info("reddit")

    resolved = await YtDlpClient(extract=extract).resolve("https://reddit.test/post", ["dash-VIDEO-1", "dash-AUDIO-1"])

    assert resolved == {
        "dash-VIDEO-1": Resolved("https://media.test/reddit/dash-VIDEO-1", HEADERS),
        "dash-AUDIO-1": Resolved("https://media.test/reddit/dash-AUDIO-1", HEADERS),
    }
    assert calls == ["https://reddit.test/post"]


@pytest.mark.asyncio
async def test_resolve_says_which_formats_are_fragmented() -> None:
    resolved = await _client(_info("reddit")).resolve("https://reddit.test/post", ["hls-1875", "dash-VIDEO-1"])

    assert resolved["hls-1875"].fragmented is True
    assert resolved["dash-VIDEO-1"].fragmented is False


@pytest.mark.asyncio
async def test_open_gives_the_page_and_every_formats_url_from_one_extraction() -> None:
    # The player needs both at once, and a YouTube extraction takes seconds.
    calls: list[str] = []

    def extract(url: str) -> dict[str, Any]:
        calls.append(url)
        return _info("reddit")

    info, resolved = await YtDlpClient(extract=extract).open("https://reddit.test/post")

    assert info.extractor == _info("reddit")["extractor_key"]
    assert set(resolved) == {f.id for f in info.formats}
    assert resolved["dash-AUDIO-1"] == Resolved("https://media.test/reddit/dash-AUDIO-1", HEADERS)
    assert calls == ["https://reddit.test/post"]


@pytest.mark.asyncio
async def test_open_refuses_what_extract_refuses() -> None:
    playlist = {"_type": "playlist", "id": "PL1", "title": "A list", "entries": [_info("youtube")]}

    with pytest.raises(Error) as caught:
        await _client(playlist).open("https://www.youtube.com/playlist?list=PL1")

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


@pytest.mark.asyncio
async def test_resolve_refuses_a_format_the_site_no_longer_offers() -> None:
    with pytest.raises(Error) as caught:
        await _client(_info("vimeo")).resolve("http://vimeo.com/1", ["http-4320p"])

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- failures -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_an_extraction_that_hangs_gives_up_and_can_be_retried() -> None:
    def extract(_url: str) -> dict[str, Any]:
        time.sleep(0.3)
        return _info("vimeo")

    with pytest.raises(Error) as caught:
        await YtDlpClient(extract=extract, timeout_s=0.05).extract("http://vimeo.com/1")

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)


@pytest.mark.asyncio
async def test_a_library_failure_is_classified() -> None:
    def extract(_url: str) -> dict[str, Any]:
        raise RuntimeError("ERROR: Unsupported URL: https://example.test/file.zip")

    with pytest.raises(Error) as caught:
        await YtDlpClient(extract=extract).extract("https://example.test/file.zip")

    assert caught.value.type == ErrorType.UNSUPPORTED_URL


@pytest.mark.parametrize(
    ("message", "code"),
    [
        ("Unsupported URL: https://example.test/file.zip", Code.BAD_REQUEST),
        ("'not a link' is not a valid URL.", Code.BAD_REQUEST),
        ("Private video. Sign in if you've been granted access to this video", Code.NOT_FOUND),
        ("Video unavailable. This video has been removed by the uploader", Code.NOT_FOUND),
        ("Sign in to confirm your age. This video may be inappropriate for some users.", Code.FORBIDDEN),
        ("Join this channel to get access to members-only content like this video", Code.FORBIDDEN),
        ("The uploader has not made this video available in your country", Code.FORBIDDEN),
        # YouTube's bot check clears with time: retry, don't give up.
        ("Sign in to confirm you're not a bot. This helps protect our community.", Code.BAD_GATEWAY),
        ("Unable to download webpage: <urlopen error timed out>", Code.BAD_GATEWAY),
        # A page that is not there will not be there on a retry either.
        ("Unable to download webpage: HTTP Error 404: Not Found", Code.NOT_FOUND),
    ],
)
def test_classify(message: str, code: Code) -> None:
    assert classify(RuntimeError(f"ERROR: [site] x: {message}")).code == code


def test_only_the_unknown_is_retryable() -> None:
    assert classify(RuntimeError("Unsupported URL: x")).retry_able is False
    assert classify(RuntimeError("Private video")).retry_able is False
    assert classify(RuntimeError("who knows")).retry_able is True


# --- version -------------------------------------------------------------------


def test_the_version_is_empty_when_yt_dlp_is_not_installed(monkeypatch: pytest.MonkeyPatch) -> None:
    # GET /settings reports it; a missing package is a blank there, not a 500.
    def not_installed(name: str) -> str:
        raise metadata.PackageNotFoundError(name)

    monkeypatch.setattr(metadata, "version", not_installed)

    assert ytdlp_version() == ""


# --- download_format -------------------------------------------------------------

PAGE = "https://www.dailymotion.com/video/x8"


class FakeDownload:
    """Stands in for yt-dlp's ``download()``.

    It calls the progress hook with each status in turn, then raises ``fail``
    (after the status numbered ``fail_after``, if given) or writes the file.
    """

    def __init__(
        self,
        statuses: list[dict[str, Any]] | None = None,
        *,
        fail: BaseException | None = None,
        fail_after: int | None = None,
    ) -> None:
        self.statuses = statuses or []
        self.fail = fail
        self.fail_after = fail_after
        self.params: dict[str, Any] = {}
        self.url = ""

    def __call__(self, params: dict[str, Any], url: str) -> None:
        self.params, self.url = params, url
        hook = params["progress_hooks"][0]
        for index, status in enumerate(self.statuses):
            hook(status)
            if self.fail is not None and self.fail_after == index:
                raise self.fail
        if self.fail is not None:
            raise self.fail
        Path(params["outtmpl"]).write_bytes(b"ts")


def _download(
    fake: FakeDownload, tmp_path: Path, *, rate_bps: int = 0, stop: Any = lambda: False
) -> list[FormatProgress]:
    seen: list[FormatProgress] = []
    YtDlpClient(extract=lambda _url: {}, download=fake).download_format(
        PAGE,
        "hls-1080",
        tmp_path / "video.part",
        concurrency=4,
        rate_bps=rate_bps,
        on_progress=seen.append,
        should_stop=stop,
    )
    return seen


def test_download_format_asks_yt_dlp_for_that_format_into_our_path(tmp_path: Path) -> None:
    fake = FakeDownload()
    _download(fake, tmp_path)

    assert fake.url == PAGE
    params = fake.params
    assert params["format"] == "hls-1080"
    assert params["outtmpl"] == str(tmp_path / "video.part")
    assert params["noplaylist"] is True
    assert params["continuedl"] is True
    assert params["fixup"] == "never"
    assert params["postprocessors"] == []
    assert params["skip_unavailable_fragments"] is False
    assert params["external_downloader"] == {"m3u8": "native", "dash": "native"}
    assert params["concurrent_fragment_downloads"] == 4
    assert "ratelimit" not in params


def test_a_failing_fragment_is_retried_with_a_short_backoff(tmp_path: Path) -> None:
    # yt-dlp's command line retries ten times, but as a library it retries
    # nothing unless asked: one 503 from a CDN failed a whole attempt.
    fake = FakeDownload()
    _download(fake, tmp_path)

    params = fake.params
    assert (params["retries"], params["fragment_retries"]) == (10, 10)
    for kind in ("http", "fragment"):
        sleep = params["retry_sleep_functions"][kind]
        # yt-dlp passes the number of retries already made.
        assert [sleep(n=n) for n in range(6)] == [1, 2, 4, 5, 5, 5]


def test_fragments_left_mid_download_start_afresh(tmp_path: Path) -> None:
    # yt-dlp cannot resume a fragment whose ``.part`` already holds all of it
    # (the integration test shows how), so those start over. Finished
    # fragments, and what is already joined into the part, stay.
    for name in ("video.part.part", "video.part.ytdl", "video.part.part-Frag2", "video.part.part-Frag3.part"):
        (tmp_path / name).write_bytes(b"x")
    present: list[str] = []

    def download(params: dict[str, Any], url: str) -> None:
        present.extend(sorted(p.name for p in tmp_path.iterdir()))

    YtDlpClient(extract=lambda _url: {}, download=download).download_format(
        PAGE,
        "hls-1080",
        tmp_path / "video.part",
        concurrency=4,
        rate_bps=0,
        on_progress=lambda _progress: None,
        should_stop=lambda: False,
    )

    assert present == ["video.part.part", "video.part.part-Frag2", "video.part.ytdl"]


def test_a_rate_limit_is_passed_only_when_set(tmp_path: Path) -> None:
    fake = FakeDownload()
    _download(fake, tmp_path, rate_bps=500_000)

    assert fake.params["ratelimit"] == 500_000


def test_progress_is_the_estimate_until_the_exact_size_arrives(tmp_path: Path) -> None:
    fake = FakeDownload([
        {"status": "downloading", "downloaded_bytes": 100, "total_bytes_estimate": 1000, "speed": 50.5, "eta": 18},
        {"status": "finished", "downloaded_bytes": 950, "total_bytes": 950},
    ])

    assert _download(fake, tmp_path) == [
        FormatProgress(downloaded_bytes=100, total_bytes=1000, speed_bps=50, eta_seconds=18),
        FormatProgress(downloaded_bytes=950, total_bytes=950, speed_bps=None, eta_seconds=None),
    ]


def test_an_estimate_below_what_has_arrived_is_raised_to_it(tmp_path: Path) -> None:
    fake = FakeDownload([{"status": "downloading", "downloaded_bytes": 500, "total_bytes_estimate": 400}])

    assert _download(fake, tmp_path)[0].total_bytes == 500


def test_a_part_already_on_disk_counts_as_downloaded(tmp_path: Path) -> None:
    # A retry after the part finished: yt-dlp skips it and reports only its size.
    fake = FakeDownload([{"status": "finished", "total_bytes": 950}])

    assert _download(fake, tmp_path) == [
        FormatProgress(downloaded_bytes=950, total_bytes=950, speed_bps=None, eta_seconds=None)
    ]


def test_asking_to_stop_raises_through_yt_dlp(tmp_path: Path) -> None:
    fake = FakeDownload([
        {"status": "downloading", "downloaded_bytes": 1},
        {"status": "downloading", "downloaded_bytes": 2},
    ])
    asked = iter([False, True])

    with pytest.raises(DownloadStopped):
        _download(fake, tmp_path, stop=lambda: next(asked, True))


def test_a_stop_asked_before_starting_never_runs_yt_dlp(tmp_path: Path) -> None:
    fake = FakeDownload()

    with pytest.raises(DownloadStopped):
        _download(fake, tmp_path, stop=lambda: True)

    assert fake.url == ""


class FakeDownloadError(Exception):
    """yt-dlp's ``DownloadError`` keeps what it wraps in ``exc_info``, not in ``__cause__``."""

    def __init__(self, message: str, wrapped: BaseException) -> None:
        super().__init__(message)
        self.exc_info = (type(wrapped), wrapped, None)


def _disk_full() -> FakeDownloadError:
    # The shape the probe saw: DownloadError <- UnavailableVideoError <- OSError(ENOSPC).
    try:
        try:
            raise OSError(errno.ENOSPC, "No space left on device")
        except OSError as full:
            raise RuntimeError("Unable to download video: [Errno 28] No space left on device") from full
    except RuntimeError as unavailable:
        return FakeDownloadError("ERROR: Unable to download video", unavailable)


def test_a_full_disk_comes_out_as_its_oserror(tmp_path: Path) -> None:
    fake = FakeDownload([{"status": "downloading", "downloaded_bytes": 1}], fail=_disk_full(), fail_after=0)

    with pytest.raises(OSError) as caught:
        _download(fake, tmp_path)

    assert caught.value.errno == errno.ENOSPC


def test_a_failure_before_any_progress_is_an_extraction_error(tmp_path: Path) -> None:
    fake = FakeDownload(fail=RuntimeError("ERROR: [dailymotion] x8: Video unavailable"))

    with pytest.raises(Error) as caught:
        _download(fake, tmp_path)

    assert (caught.value.code, caught.value.retry_able) == (Code.NOT_FOUND, False)


def test_a_failure_after_progress_is_a_retryable_transfer_error(tmp_path: Path) -> None:
    fake = FakeDownload(
        [{"status": "downloading", "downloaded_bytes": 1}],
        fail=RuntimeError("ERROR: fragment 3 not found: HTTP Error 403: Forbidden"),
        fail_after=0,
    )

    with pytest.raises(Error) as caught:
        _download(fake, tmp_path)

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)
    assert (caught.value.message or "").startswith("Download failed")
