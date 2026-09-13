from pathlib import Path
from typing import Any

import httpx
import pytest
from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Downloader
from src.service.download.post_process import FfmpegPostProcessor

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

BODY = b"z" * 500


class FakeYouTube:
    async def fetch_info(self, video_id: str) -> Any:
        raise AssertionError("the worker must not re-fetch info")

    async def stream_url(self, video_id: str, itag: int) -> str:
        return f"https://cdn.test/{video_id}/{itag}"


def _worker(tmp_path: Path, control: DownloadControl, client: httpx.AsyncClient) -> DownloadWorker:
    return DownloadWorker(
        name="test-worker",
        repo=TaskDatabaseRepo(),
        client=FakeYouTube(),
        downloader=Downloader(client, chunk_size=64, flush_interval_ms=0),
        post_processor=FfmpegPostProcessor("ffmpeg"),
        control=control,
        downloads_root=tmp_path,
        max_attempts=3,
    )


async def _task(**overrides: Any) -> Task:
    fields: dict[str, Any] = {
        "source_url": "https://youtu.be/x",
        "platform": Platform.YOUTUBE,
        "video_id": "x",
        "preset": Preset.P720,
        "kind": Kind.VIDEO,
        "status": TaskStatus.PENDING,
        "title": "clip",
        "filename": "clip.mp4",
        "video_itag": 22,
    }
    fields.update(overrides)
    return await Task.create(**fields)


async def test_a_claimed_task_downloads_and_completes(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE
    assert task.progress == 100
    assert task.file_size == 500
    assert (tmp_path / str(task.id) / "clip.mp4").read_bytes() == BODY


async def test_a_retryable_failure_requeues_with_a_backoff(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(503))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.PENDING
    assert task.attempts == 1
    assert task.next_attempt_at is not None
    assert task.error is not None


async def test_a_permanent_failure_stops_at_once(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(404))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.FAILED
    assert task.next_attempt_at is None


async def test_the_last_attempt_fails_rather_than_retrying(db: None, tmp_path: Path) -> None:
    task = await _task(attempts=2)
    transport = httpx.MockTransport(lambda request: httpx.Response(503))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.attempts == 3
    assert task.status == TaskStatus.FAILED


async def test_a_stop_request_leaves_the_partial_file(db: None, tmp_path: Path) -> None:
    task = await _task()
    control = DownloadControl()
    control.request_stop(task.id)
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, control, client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    assert (tmp_path / str(task.id) / "video.part").exists()


async def test_a_resumed_task_continues_from_the_partial_file(db: None, tmp_path: Path) -> None:
    task = await _task()
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("range"))
        return httpx.Response(206, content=BODY[100:], headers={"content-range": f"bytes 100-499/{len(BODY)}"})

    part = tmp_path / str(task.id) / "video.part"
    part.parent.mkdir(parents=True)
    part.write_bytes(BODY[:100])

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    assert seen == ["bytes=100-"]
    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE
    assert (tmp_path / str(task.id) / "clip.mp4").read_bytes() == BODY


async def test_a_direct_task_downloads_from_its_source_url(db: None, tmp_path: Path) -> None:
    task = await _task(
        platform=Platform.DIRECT,
        video_id=None,
        kind=Kind.FILE,
        video_itag=None,
        filename="file.bin",
        source_url="https://cdn.test/file.bin",
    )
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(str(request.url))
        return httpx.Response(200, content=BODY)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    assert requested == ["https://cdn.test/file.bin"]
    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE


async def test_progress_is_cumulative_across_a_two_part_download(db: None, tmp_path: Path) -> None:
    # The bug this guards: the audio part used to restart the percentage at
    # zero, so the UI ran 0-100 twice and appeared to go backwards.
    await _task(video_itag=137, audio_itag=140, total_bytes=1000)
    seen: list[tuple[int, int]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"y" * 500, headers={"content-length": "500"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        original = worker._repo.flush_progress

        async def spy(task_id: Any, **kwargs: Any) -> None:
            seen.append((kwargs["downloaded_bytes"], kwargs["progress"]))
            await original(task_id, **kwargs)

        worker._repo.flush_progress = spy  # ty: ignore[invalid-assignment]
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    downloaded = [d for d, _ in seen]
    progress = [p for _, p in seen]
    assert downloaded == sorted(downloaded), f"bytes went backwards: {downloaded}"
    assert progress == sorted(progress), f"progress went backwards: {progress}"
    assert downloaded[-1] == 1000
    assert progress[-1] == 100


async def test_completion_does_not_clobber_the_flushed_byte_counts(db: None, tmp_path: Path) -> None:
    # The bug this guards: _mark_complete saved a task object loaded at claim
    # time, writing its stale downloaded_bytes=0 over everything flushed since.
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.downloaded_bytes == 500
    assert task.total_bytes == 500
    assert task.file_size == 500
