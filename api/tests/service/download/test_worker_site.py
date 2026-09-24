"""The worker downloading a site task: one extraction per attempt, headers with every part."""

import uuid
from pathlib import Path
from typing import Any, cast

import pytest
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker

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

    def __init__(self) -> None:
        self.parts: list[tuple[str, str, dict[str, str]]] = []

    async def fetch(self, source: Any, dest: Path, **kwargs: Any) -> int:
        url = await source.current()
        self.parts.append((dest.name, url, source.headers))
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"x" * 10)
        return 10


class TouchingPostProcessor:
    def __init__(self) -> None:
        self.parts: dict[str, Path] = {}

    async def run(self, task: Any, parts: dict[str, Path], destination: Path) -> None:
        self.parts = dict(parts)
        destination.write_bytes(b"done")


def _worker(tmp_path: Path, client: FakeSiteClient, engine: RecordingEngine, post: TouchingPostProcessor) -> DownloadWorker:
    return DownloadWorker(
        name="test",
        repo=cast(Any, object()),
        segment_repo=cast(Any, FakeSegmentRepo()),
        client=cast(Any, client),
        engine=cast(Any, engine),
        post_processor=cast(Any, post),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=tmp_path,
        max_attempts=3,
        segments=4,
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
