"""The worker downloading a site download: one extraction per attempt, headers with every part."""

from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.type import DownloadStatus, MediaKind, Preset
from src.lib.media.sidecar import folder_listing, match_sidecars
from src.lib.site.subtitles import SiteSubtitle
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Stopped
from src.service.download.paths import remove_work_files
from src.service.download.progress import AggregateSample

from tests.service.download.memory import MemoryFiles
from tests.service.download.workers import FakeCollections, FlushRecordingRepo, RecordingTotals, site_row, worker
from tests.sites import HEADERS, FakeSiteClient, media_url, site_info


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


class TouchingPostProcessor:
    def __init__(self) -> None:
        self.parts: dict[str, Path] = {}
        self.fragmented: frozenset[str] = frozenset()

    async def run(
        self, download: Any, parts: dict[str, Path], destination: Path, *, fragmented: frozenset[str] = frozenset()
    ) -> None:
        self.parts = dict(parts)
        self.fragmented = fragmented
        destination.write_bytes(b"done")


def _worker(
    tmp_path: Path,
    files: MemoryFiles,
    client: FakeSiteClient,
    engine: RecordingEngine | None = None,
    post: TouchingPostProcessor | None = None,
    **kwargs: Any,
) -> DownloadWorker:
    return worker(
        tmp_path,
        files=files,
        client=client,
        engine=engine or RecordingEngine(),
        post=post or TouchingPostProcessor(),
        **kwargs,
    )


async def _path(files: MemoryFiles, row: Any) -> str:
    single = await files.single(row.id)
    assert single is not None
    return single.path


@pytest.mark.asyncio
async def test_every_part_comes_from_one_extraction(tmp_path: Path) -> None:
    files, client = MemoryFiles(), FakeSiteClient(site_info("youtube"))
    row = await site_row(files)

    await _worker(tmp_path, files, client).run_task(row)

    assert client.resolved == [("https://youtu.be/dQw4w9WgXcQ", ["137", "140"])]
    assert row.status == DownloadStatus.COMPLETE
    assert row.folder == str(row.id)


@pytest.mark.asyncio
async def test_an_unplanned_video_is_planned_from_one_extraction(tmp_path: Path) -> None:
    # A collection's video, added with its number and no formats (v0.5).
    files, client = MemoryFiles(), FakeSiteClient(site_info("vimeo"))
    row = await site_row(
        files,
        filename="03_",
        source_url="http://vimeo.com/75629013",
        extractor="Vimeo",
        video_format=None,
        audio_format=None,
        title="listed title",
        preset=Preset.P720,
        position=3,
    )

    await _worker(tmp_path, files, client).run_task(row)

    assert client.opened == ["http://vimeo.com/75629013"]
    # The plan's extraction also gave the URLs.
    assert client.resolved == []
    assert (await _path(files, row)).startswith("03_")
    assert row.site_detail.video_format
    assert row.title == site_info("vimeo").title
    assert row.status == DownloadStatus.COMPLETE


@pytest.mark.asyncio
async def test_a_stale_format_is_re_planned_once(tmp_path: Path) -> None:
    files, client = MemoryFiles(), FakeSiteClient(site_info("vimeo"))
    row = await site_row(
        files,
        filename="Key_9999p.mp4",
        source_url="http://vimeo.com/75629013",
        extractor="Vimeo",
        video_format="http-9999p",
        audio_format=None,
        preset=Preset.P720,
    )

    await _worker(tmp_path, files, client).run_task(row)

    assert row.site_detail.video_format != "http-9999p"
    assert client.opened == ["http://vimeo.com/75629013"]
    assert row.status == DownloadStatus.COMPLETE


def _collection(tmp_path: Path) -> FakeCollections:
    (tmp_path / "List").mkdir()
    return FakeCollections("List")


@pytest.mark.asyncio
async def test_a_collection_video_finishes_into_the_collection_folder(tmp_path: Path) -> None:
    files, collections = MemoryFiles(), _collection(tmp_path)
    row = await site_row(files, filename="02_Rick_1080p.mp4", collection_id=collections.collection.id)

    await _worker(tmp_path, files, FakeSiteClient(site_info("youtube")), collections=collections).run_task(row)

    assert (row.folder, await _path(files, row)) == ("List", "02_Rick_1080p.mp4")
    assert (tmp_path / "List" / "02_Rick_1080p.mp4").read_bytes() == b"done"
    assert not (tmp_path / str(row.id)).exists()


@pytest.mark.asyncio
async def test_each_change_to_a_collection_video_refreshes_its_collection(tmp_path: Path) -> None:
    files, collections, totals = MemoryFiles(), _collection(tmp_path), RecordingTotals()
    row = await site_row(files, collection_id=collections.collection.id)

    await _worker(
        tmp_path, files, FakeSiteClient(site_info("youtube")), collections=collections, totals=totals
    ).run_task(row)

    # Once when it started, once when it finished.
    assert totals.refreshed == [collections.collection.id, collections.collection.id]


@pytest.mark.asyncio
async def test_a_standalone_download_refreshes_no_collection(tmp_path: Path) -> None:
    files, totals = MemoryFiles(), RecordingTotals()

    await _worker(tmp_path, files, FakeSiteClient(site_info("youtube")), totals=totals).run_task(
        await site_row(files)
    )

    assert totals.refreshed == []


@pytest.mark.asyncio
async def test_a_taken_name_gets_the_video_id(tmp_path: Path) -> None:
    files, collections = MemoryFiles(), _collection(tmp_path)
    (tmp_path / "List" / "Rick_1080p.mp4").write_bytes(b"other")
    row = await site_row(files, collection_id=collections.collection.id)

    await _worker(tmp_path, files, FakeSiteClient(site_info("youtube")), collections=collections).run_task(row)

    assert await _path(files, row) == "Rick_1080p_dQw4w9WgXcQ.mp4"
    assert (tmp_path / "List" / "Rick_1080p.mp4").read_bytes() == b"other"


@pytest.mark.asyncio
async def test_each_part_gets_its_own_url_and_the_format_s_headers(tmp_path: Path) -> None:
    files, engine, post = MemoryFiles(), RecordingEngine(), TouchingPostProcessor()

    await _worker(tmp_path, files, FakeSiteClient(site_info("youtube")), engine, post).run_task(await site_row(files))

    assert [(url, headers) for _, url, headers in engine.parts] == [
        (media_url("youtube", "137"), HEADERS),
        (media_url("youtube", "140"), HEADERS),
    ]
    assert sorted(post.parts) == ["audio", "video"]


@pytest.mark.asyncio
async def test_a_combined_format_is_a_single_video_part(tmp_path: Path) -> None:
    files, client, post = MemoryFiles(), FakeSiteClient(site_info("vimeo")), TouchingPostProcessor()
    row = await site_row(
        files, source_url="http://vimeo.com/75629013", extractor="Vimeo", video_format="http-1080p", audio_format=None
    )

    await _worker(tmp_path, files, client, post=post).run_task(row)

    assert client.resolved == [("http://vimeo.com/75629013", ["http-1080p"])]
    assert list(post.parts) == ["video"]


@pytest.mark.asyncio
async def test_a_vanished_format_with_nothing_to_re_plan_to_fails_the_download(tmp_path: Path) -> None:
    # The format is gone, and the page no longer has audio for an MP3: one
    # re-plan, then the download fails for good.
    info = site_info("youtube")
    files = MemoryFiles()
    client = FakeSiteClient(replace(info, formats=[f for f in info.formats if not f.has_audio]))
    row = await site_row(
        files, video_format=None, audio_format="99999", preset=Preset.MP3, media_kind=MediaKind.AUDIO
    )

    await _worker(tmp_path, files, client).run_task(row)

    assert row.status == DownloadStatus.FAILED
    assert client.opened == ["https://youtu.be/dQw4w9WgXcQ"]


async def _dailymotion(files: MemoryFiles) -> Any:
    return await site_row(
        files,
        filename="Clip_1080p.mp4",
        source_url="https://www.dailymotion.com/video/x8",
        extractor="Dailymotion",
        video_format="hls-1080",
        audio_format=None,
    )


@pytest.mark.asyncio
async def test_a_fragmented_part_goes_to_yt_dlp_with_the_page_and_its_format(tmp_path: Path) -> None:
    files, engine, fragments, post = MemoryFiles(), RecordingEngine(), RecordingFragments(), TouchingPostProcessor()
    row = await _dailymotion(files)

    await _worker(
        tmp_path, files, FakeSiteClient(site_info("dailymotion")), engine, post, fragments=fragments
    ).run_task(row)

    assert fragments.calls == [("https://www.dailymotion.com/video/x8", "hls-1080", "video.part")]
    assert engine.parts == []
    assert post.fragmented == frozenset({"video"})
    assert row.status == DownloadStatus.COMPLETE


@pytest.mark.asyncio
async def test_a_mixed_plan_sends_each_part_down_its_own_path(tmp_path: Path) -> None:
    # Reddit's Best: the taller HLS video, and its HTTPS audio.
    files, post, repo = MemoryFiles(), TouchingPostProcessor(), FlushRecordingRepo()
    engine, fragments = RecordingEngine(report=True), RecordingFragments(report=True)
    row = await site_row(
        files,
        source_url="https://www.reddit.com/r/videos/comments/6rrwyj/x/",
        extractor="Reddit",
        video_format="hls-1875",
        audio_format="dash-AUDIO-1",
    )

    await _worker(
        tmp_path, files, FakeSiteClient(site_info("reddit")), engine, post, fragments=fragments, repo=repo
    ).run_task(row)

    assert [call[1] for call in fragments.calls] == ["hls-1875"]
    assert [url for _, url, _ in engine.parts] == [media_url("reddit", "dash-AUDIO-1")]
    assert sorted(post.parts) == ["audio", "video"]
    assert post.fragmented == frozenset({"video"})
    # The audio part's progress continues from the video's 7 bytes.
    assert [fields["downloaded_bytes"] for fields in repo.flushed] == [7, 17]


@pytest.mark.asyncio
async def test_an_http_plan_never_touches_the_fragment_path(tmp_path: Path) -> None:
    files, fragments, post = MemoryFiles(), RecordingFragments(), TouchingPostProcessor()

    await _worker(
        tmp_path, files, FakeSiteClient(site_info("youtube")), post=post, fragments=fragments
    ).run_task(await site_row(files))

    assert fragments.calls == []
    assert post.fragmented == frozenset()


@pytest.mark.asyncio
async def test_a_stop_on_the_fragment_path_is_a_stop_not_a_failure(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await _dailymotion(files)

    await _worker(
        tmp_path, files, FakeSiteClient(site_info("dailymotion")), fragments=RecordingFragments(fail=Stopped())
    ).run_task(row)

    assert row.status == DownloadStatus.DOWNLOADING


@pytest.mark.asyncio
async def test_a_worker_without_a_fragment_downloader_fails_a_fragmented_part(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await _dailymotion(files)

    await _worker(tmp_path, files, FakeSiteClient(site_info("dailymotion"))).run_task(row)

    assert row.status == DownloadStatus.FAILED


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


def _subtitled_worker(tmp_path: Path, files: MemoryFiles, server: FakeSubtitleServer) -> DownloadWorker:
    w = _worker(tmp_path, files, FakeSiteClient(_with_subtitles()))
    w._fetch_subtitle = server
    return w


@pytest.mark.asyncio
async def test_a_finished_video_saves_the_page_s_subtitles_beside_it(tmp_path: Path) -> None:
    files, server = MemoryFiles(), FakeSubtitleServer()
    row = await site_row(files)

    await _subtitled_worker(tmp_path, files, server).run_task(row)

    folder = tmp_path / str(row.id)
    assert row.status == DownloadStatus.COMPLETE
    assert sorted(p.name for p in folder.iterdir() if p.suffix != ".part") == [
        "Rick_1080p.en.auto.vtt",
        "Rick_1080p.en.vtt",
        "Rick_1080p.es.srt",
        "Rick_1080p.mp4",
    ]
    assert (folder / "Rick_1080p.en.vtt").read_text() == "WEBVTT from https://yt.test/en.vtt\n"
    assert server.fetched[0] == ("https://yt.test/en.vtt", {"User-Agent": "UA"})
    # And #101 finds them, with their languages, when the download is played.
    found = match_sidecars("Rick_1080p.mp4", folder_listing(folder))
    # Captions after the rest, so English picks the real subtitles.
    assert [(sidecar.path, sidecar.language, sidecar.automatic) for sidecar in found] == [
        ("Rick_1080p.en.vtt", "en", False),
        ("Rick_1080p.es.srt", "es", False),
        ("Rick_1080p.en.auto.vtt", "en", True),
    ]
    # Deleting the download's work files takes them too.
    remove_work_files(tmp_path, row.id)
    assert not folder.exists()


@pytest.mark.asyncio
async def test_a_subtitle_that_fails_is_skipped_and_the_download_still_completes(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await site_row(files)

    await _subtitled_worker(tmp_path, files, FakeSubtitleServer(failing={"https://yt.test/es.srt"})).run_task(row)

    assert row.status == DownloadStatus.COMPLETE
    assert sorted(p.name for p in (tmp_path / str(row.id)).iterdir() if p.suffix != ".part") == [
        "Rick_1080p.en.auto.vtt",
        "Rick_1080p.en.vtt",
        "Rick_1080p.mp4",
    ]


@pytest.mark.asyncio
async def test_an_extraction_that_fails_leaves_the_download_complete(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await site_row(files)
    w = _subtitled_worker(tmp_path, files, FakeSubtitleServer())

    async def broken(_url: str) -> Any:
        raise Error.create(code=Code.BAD_GATEWAY, message="bot check", error_type=ErrorType.EXTERNAL_API_ERROR)

    w._client.extract = broken  # ty: ignore[invalid-assignment]
    await w.run_task(row)

    assert row.status == DownloadStatus.COMPLETE


@pytest.mark.asyncio
async def test_an_audio_download_saves_no_subtitles(tmp_path: Path) -> None:
    files, server = MemoryFiles(), FakeSubtitleServer()
    row = await site_row(files, media_kind=MediaKind.AUDIO)

    await _subtitled_worker(tmp_path, files, server).run_task(row)

    assert server.fetched == []
