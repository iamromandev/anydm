"""The worker downloading a site task: one extraction per attempt, headers with every part."""

import uuid
from dataclasses import replace
from pathlib import Path
from typing import Any, cast

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.media.sidecar import folder_listing, match_sidecars
from src.lib.site.subtitles import SiteSubtitle
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker, remove_task_files
from src.service.download.downloader import Stopped
from src.service.download.progress import AggregateSample

from tests.sites import HEADERS, FakeSiteClient, media_url, site_info


class FakeRow:
    """A claimed YouTube task at 1080p: two parts, video and audio."""

    def __init__(self, **overrides: Any) -> None:
        self.id = uuid.uuid4()
        self.source_url = "https://youtu.be/dQw4w9WgXcQ"
        self.platform = Platform.SITE
        self.extractor = "Youtube"
        self.video_id = "dQw4w9WgXcQ"
        self.preset = Preset.P1080
        self.kind = Kind.VIDEO
        self.title = "Rick"
        self.filename = "Rick_1080p.mp4"
        self.video_format: str | None = "137"
        self.audio_format: str | None = "140"
        self.total_bytes: int | None = None
        self.attempts = 0
        self.status = TaskStatus.DOWNLOADING
        for key, value in overrides.items():
            setattr(self, key, value)

    async def save(self, update_fields: list[str]) -> None:
        return None

    async def refresh_from_db(self) -> None:
        return None


class FakeSegmentRepo:
    async def progress(self, task_id: uuid.UUID, part: str) -> int:
        return 0

    async def clear(self, task_id: uuid.UUID, part: str | None = None) -> None:
        return None


class RecordingEngine:
    """Writes a few bytes per part, noting the URL and headers each part was given."""

    def __init__(self, *, report: bool = False) -> None:
        self.parts: list[tuple[str, str, dict[str, str]]] = []
        self.report = report

    async def fetch(self, source: Any, dest: Path, **kwargs: Any) -> int:
        url = await source.current()
        self.parts.append((dest.name, url, source.headers))
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"x" * 10)
        if self.report:
            await kwargs["on_sample"](AggregateSample(10, 10, 100, 0, None, ()))
        return 10


class RecordingFragments:
    """Stands in for FragmentDownloader: notes what yt-dlp would have been asked for."""

    def __init__(self, *, fail: BaseException | None = None, report: bool = False) -> None:
        self.calls: list[tuple[str, str, str]] = []
        self.fail = fail
        self.report = report

    async def fetch(self, page_url: str, format_id: str, destination: Path, *, on_sample: Any, should_stop: Any) -> int:
        self.calls.append((page_url, format_id, destination.name))
        if self.fail is not None:
            raise self.fail
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"f" * 7)
        if self.report:
            await on_sample(AggregateSample(7, 7, 100, 0, None, ()))
        return 7


class FlushRecordingRepo:
    def __init__(self) -> None:
        self.flushed: list[int] = []

    async def flush_progress(self, task_id: uuid.UUID, **fields: Any) -> None:
        self.flushed.append(fields["downloaded_bytes"])


class TouchingPostProcessor:
    def __init__(self) -> None:
        self.parts: dict[str, Path] = {}
        self.fragmented: frozenset[str] = frozenset()

    async def run(
        self, task: Any, parts: dict[str, Path], destination: Path, *, fragmented: frozenset[str] = frozenset()
    ) -> None:
        self.parts = dict(parts)
        self.fragmented = fragmented
        destination.write_bytes(b"done")


def _worker(
    tmp_path: Path,
    client: FakeSiteClient,
    engine: RecordingEngine,
    post: TouchingPostProcessor,
    *,
    fragments: RecordingFragments | None = None,
    repo: Any = None,
) -> DownloadWorker:
    return DownloadWorker(
        name="test",
        repo=cast(Any, repo or object()),
        segment_repo=cast(Any, FakeSegmentRepo()),
        client=cast(Any, client),
        engine=cast(Any, engine),
        post_processor=cast(Any, post),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=tmp_path,
        max_attempts=3,
        segments=4,
        fragments=cast(Any, fragments),
    )


@pytest.mark.asyncio
async def test_every_part_comes_from_one_extraction(tmp_path: Path) -> None:
    client = FakeSiteClient(site_info("youtube"))
    row = FakeRow()

    await _worker(tmp_path, client, RecordingEngine(), TouchingPostProcessor()).run_task(cast(Any, row))

    assert client.resolved == [("https://youtu.be/dQw4w9WgXcQ", ["137", "140"])]
    assert row.status == TaskStatus.COMPLETE


@pytest.mark.asyncio
async def test_each_part_gets_its_own_url_and_the_format_s_headers(tmp_path: Path) -> None:
    engine = RecordingEngine()
    post = TouchingPostProcessor()

    await _worker(tmp_path, FakeSiteClient(site_info("youtube")), engine, post).run_task(cast(Any, FakeRow()))

    assert [(url, headers) for _, url, headers in engine.parts] == [
        (media_url("youtube", "137"), HEADERS),
        (media_url("youtube", "140"), HEADERS),
    ]
    assert sorted(post.parts) == ["audio", "video"]


@pytest.mark.asyncio
async def test_a_combined_format_is_a_single_video_part(tmp_path: Path) -> None:
    client = FakeSiteClient(site_info("vimeo"))
    engine = RecordingEngine()
    post = TouchingPostProcessor()
    row = FakeRow(source_url="http://vimeo.com/75629013", extractor="Vimeo", video_format="http-1080p", audio_format=None)

    await _worker(tmp_path, client, engine, post).run_task(cast(Any, row))

    assert client.resolved == [("http://vimeo.com/75629013", ["http-1080p"])]
    assert list(post.parts) == ["video"]


@pytest.mark.asyncio
async def test_a_format_the_site_no_longer_offers_fails_the_task(tmp_path: Path) -> None:
    row = FakeRow(video_format="99999")

    await _worker(tmp_path, FakeSiteClient(site_info("youtube")), RecordingEngine(), TouchingPostProcessor()).run_task(
        cast(Any, row)
    )

    assert row.status == TaskStatus.FAILED


def _dailymotion() -> FakeRow:
    return FakeRow(
        source_url="https://www.dailymotion.com/video/x8",
        extractor="Dailymotion",
        filename="Clip_1080p.mp4",
        video_format="hls-1080",
        audio_format=None,
    )


@pytest.mark.asyncio
async def test_a_fragmented_part_goes_to_yt_dlp_with_the_page_and_its_format(tmp_path: Path) -> None:
    engine, fragments, post = RecordingEngine(), RecordingFragments(), TouchingPostProcessor()
    row = _dailymotion()

    await _worker(tmp_path, FakeSiteClient(site_info("dailymotion")), engine, post, fragments=fragments).run_task(
        cast(Any, row)
    )

    assert fragments.calls == [("https://www.dailymotion.com/video/x8", "hls-1080", "video.part")]
    assert engine.parts == []
    assert post.fragmented == frozenset({"video"})
    assert row.status == TaskStatus.COMPLETE


@pytest.mark.asyncio
async def test_a_mixed_plan_sends_each_part_down_its_own_path(tmp_path: Path) -> None:
    # Reddit's Best: the taller HLS video, and its HTTPS audio.
    engine, fragments, post = RecordingEngine(report=True), RecordingFragments(report=True), TouchingPostProcessor()
    repo = FlushRecordingRepo()
    row = FakeRow(
        source_url="https://www.reddit.com/r/videos/comments/6rrwyj/x/",
        extractor="Reddit",
        video_format="hls-1875",
        audio_format="dash-AUDIO-1",
    )

    await _worker(tmp_path, FakeSiteClient(site_info("reddit")), engine, post, fragments=fragments, repo=repo).run_task(
        cast(Any, row)
    )

    assert [call[1] for call in fragments.calls] == ["hls-1875"]
    assert [url for _, url, _ in engine.parts] == [media_url("reddit", "dash-AUDIO-1")]
    assert sorted(post.parts) == ["audio", "video"]
    assert post.fragmented == frozenset({"video"})
    # The audio part's progress continues from the video's 7 bytes.
    assert repo.flushed == [7, 17]


@pytest.mark.asyncio
async def test_an_http_plan_never_touches_the_fragment_path(tmp_path: Path) -> None:
    fragments, post = RecordingFragments(), TouchingPostProcessor()

    await _worker(tmp_path, FakeSiteClient(site_info("youtube")), RecordingEngine(), post, fragments=fragments).run_task(
        cast(Any, FakeRow())
    )

    assert fragments.calls == []
    assert post.fragmented == frozenset()


@pytest.mark.asyncio
async def test_a_stop_on_the_fragment_path_is_a_stop_not_a_failure(tmp_path: Path) -> None:
    row = _dailymotion()

    await _worker(
        tmp_path,
        FakeSiteClient(site_info("dailymotion")),
        RecordingEngine(),
        TouchingPostProcessor(),
        fragments=RecordingFragments(fail=Stopped()),
    ).run_task(cast(Any, row))

    assert row.status == TaskStatus.DOWNLOADING


@pytest.mark.asyncio
async def test_a_worker_without_a_fragment_downloader_fails_a_fragmented_part(tmp_path: Path) -> None:
    row = _dailymotion()

    await _worker(
        tmp_path, FakeSiteClient(site_info("dailymotion")), RecordingEngine(), TouchingPostProcessor()
    ).run_task(cast(Any, row))

    assert row.status == TaskStatus.FAILED


# --- a site's subtitles, saved beside the download (#102) --------------------------------

def _with_subtitles() -> Any:
    return replace(
        site_info("youtube"),
        subtitles=[
            SiteSubtitle("en", "English", False, "vtt", "https://yt.test/en.vtt", {"User-Agent": "UA"}),
            SiteSubtitle("es", "Spanish", False, "srt", "https://yt.test/es.srt"),
            SiteSubtitle("en", "English (auto-generated)", True, "vtt", "https://yt.test/auto.vtt"),
        ],
    )


class FakeSubtitleServer:
    def __init__(self, failing: set[str] | None = None) -> None:
        self.failing = failing or set()
        self.fetched: list[tuple[str, dict[str, str]]] = []

    async def __call__(self, url: str, headers: Any) -> bytes:
        self.fetched.append((url, dict(headers)))
        if url in self.failing:
            raise Error.create(code=Code.BAD_GATEWAY, message="nope", error_type=ErrorType.EXTERNAL_API_ERROR)
        return f"WEBVTT from {url}\n".encode()


def _subtitled_worker(tmp_path: Path, server: FakeSubtitleServer) -> DownloadWorker:
    worker = _worker(tmp_path, FakeSiteClient(_with_subtitles()), RecordingEngine(), TouchingPostProcessor())
    worker._fetch_subtitle = server
    return worker


@pytest.mark.asyncio
async def test_a_finished_video_saves_the_page_s_subtitles_beside_it(tmp_path: Path) -> None:
    row = FakeRow()
    server = FakeSubtitleServer()

    await _subtitled_worker(tmp_path, server).run_task(cast(Any, row))

    folder = tmp_path / str(row.id)
    assert row.status == TaskStatus.COMPLETE
    assert sorted(p.name for p in folder.iterdir() if p.suffix != ".part") == [
        "Rick_1080p.en.auto.vtt", "Rick_1080p.en.vtt", "Rick_1080p.es.srt", "Rick_1080p.mp4",
    ]
    assert (folder / "Rick_1080p.en.vtt").read_text() == "WEBVTT from https://yt.test/en.vtt\n"
    assert server.fetched[0] == ("https://yt.test/en.vtt", {"User-Agent": "UA"})
    # And #101 finds them, with their languages, when the download is played.
    found = match_sidecars("Rick_1080p.mp4", folder_listing(folder))
    # Captions after the rest, so English picks the real subtitles.
    assert [(sidecar.path, sidecar.language, sidecar.automatic) for sidecar in found] == [
        ("Rick_1080p.en.vtt", "en", False), ("Rick_1080p.es.srt", "es", False),
        ("Rick_1080p.en.auto.vtt", "en", True),
    ]
    # Deleting the task's files takes them too.
    remove_task_files(tmp_path, row.id)
    assert not folder.exists()


@pytest.mark.asyncio
async def test_a_subtitle_that_fails_is_skipped_and_the_download_still_completes(tmp_path: Path) -> None:
    row = FakeRow()

    await _subtitled_worker(tmp_path, FakeSubtitleServer(failing={"https://yt.test/es.srt"})).run_task(cast(Any, row))

    assert row.status == TaskStatus.COMPLETE
    assert sorted(p.name for p in (tmp_path / str(row.id)).iterdir() if p.suffix != ".part") == [
        "Rick_1080p.en.auto.vtt", "Rick_1080p.en.vtt", "Rick_1080p.mp4",
    ]


@pytest.mark.asyncio
async def test_an_extraction_that_fails_leaves_the_download_complete(tmp_path: Path) -> None:
    row = FakeRow()
    worker = _subtitled_worker(tmp_path, FakeSubtitleServer())

    async def broken(_url: str) -> Any:
        raise Error.create(code=Code.BAD_GATEWAY, message="bot check", error_type=ErrorType.EXTERNAL_API_ERROR)

    worker._client.extract = broken  # ty: ignore[invalid-assignment]
    await worker.run_task(cast(Any, row))

    assert row.status == TaskStatus.COMPLETE


@pytest.mark.asyncio
async def test_an_audio_download_saves_no_subtitles(tmp_path: Path) -> None:
    row = FakeRow()
    row.kind = Kind.AUDIO
    server = FakeSubtitleServer()

    await _subtitled_worker(tmp_path, server).run_task(cast(Any, row))

    assert server.fetched == []
