from pathlib import Path
from typing import Any

import httpx
import pytest
from src.data.db.model import Segment, Task
from src.data.repo import SegmentDatabaseRepo, TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Downloader
from src.service.download.post_process import FfmpegPostProcessor
from src.service.download.segmented import SegmentedDownloader

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

BODY = b"z" * 500


class FakeYouTube:
    async def fetch_info(self, video_id: str) -> Any:
        raise AssertionError("the worker must not re-fetch info")

    async def stream_url(self, video_id: str, itag: int) -> str:
        return f"https://cdn.test/{video_id}/{itag}"


def _worker(
    tmp_path: Path,
    control: DownloadControl,
    client: httpx.AsyncClient,
    segments: int = 1,
) -> DownloadWorker:
    """``segments=1`` by default, so every pre-existing test here still
    exercises the single-stream path it was written against."""
    downloader = Downloader(client, chunk_size=64, flush_interval_ms=0)
    return DownloadWorker(
        name="test-worker",
        repo=TaskDatabaseRepo(),
        segment_repo=SegmentDatabaseRepo(),
        client=FakeYouTube(),
        engine=SegmentedDownloader(
            client,
            downloader,
            chunk_size=64,
            flush_interval_ms=0,
            min_segment_bytes=0,
            write_buffer_bytes=128,
            segment_backoff=(0.0, 0.0),
        ),
        post_processor=FfmpegPostProcessor("ffmpeg"),
        control=control,
        hub=EventHub(),
        downloads_root=tmp_path,
        max_attempts=3,
        segments=segments,
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

    # The one-byte probe precedes every transfer; what matters is that the
    # transfer itself still resumes from the partial file rather than restarting.
    assert seen == ["bytes=0-0", "bytes=100-"]
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

    # Two requests now, not one: every transfer is preceded by a one-byte range
    # probe, and both must go to the task's own source URL.
    assert requested == ["https://cdn.test/file.bin", "https://cdn.test/file.bin"]
    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE


BIG = bytes(range(256)) * 64  # 16384 bytes


def _ranged(body: bytes = BIG, seen: list[str] | None = None):
    def handler(request: httpx.Request) -> httpx.Response:
        header = request.headers.get("range")
        if seen is not None and header is not None:
            seen.append(header)
        if header is None:
            return httpx.Response(200, content=body, headers={"content-length": str(len(body))})
        start_text, _, end_text = header.removeprefix("bytes=").partition("-")
        start = int(start_text)
        end = int(end_text) if end_text else len(body) - 1
        chunk = body[start : end + 1]
        return httpx.Response(
            206,
            content=chunk,
            headers={
                "content-range": f"bytes {start}-{end}/{len(body)}",
                "content-length": str(len(chunk)),
            },
        )

    return handler


async def _direct_task(**overrides: Any) -> Task:
    return await _task(
        platform=Platform.DIRECT,
        video_id=None,
        kind=Kind.FILE,
        video_itag=None,
        filename="f.bin",
        source_url="https://cdn.test/f.bin",
        **overrides,
    )


async def test_a_segmented_download_records_its_plan_and_clears_it_when_done(
    db: None, tmp_path: Path
) -> None:
    task = await _direct_task()

    async with httpx.AsyncClient(transport=httpx.MockTransport(_ranged())) as client:
        worker = _worker(tmp_path, DownloadControl(), client, segments=4)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE
    assert (tmp_path / str(task.id) / "f.bin").read_bytes() == BIG
    # Transient state: gone once the file exists.
    assert await Segment.filter(task_id=task.id).count() == 0


async def test_an_interrupted_download_finishes_from_the_database_alone(
    db: None, tmp_path: Path
) -> None:
    """The test that proves the ordering rule.

    Stop mid-transfer, throw away every in-memory object, rebuild from the rows
    and the .part on disk, and finish. A watermark that ever ran ahead of the
    disk produces a file of the right size and the wrong bytes here.
    """

    class StopsPartWay(DownloadControl):
        """Let some bytes land before pulling the plug.

        Stopping before the first chunk would make the byte comparison below
        vacuously true — there would be nothing claimed and nothing to compare.
        """

        def __init__(self, after: int) -> None:
            super().__init__()
            self._left = after

        def is_stopping(self, task_id: Any) -> bool:
            self._left -= 1
            return self._left <= 0

    task = await _direct_task()

    async with httpx.AsyncClient(transport=httpx.MockTransport(_ranged())) as client:
        worker = _worker(tmp_path, StopsPartWay(40), client, segments=4)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    # Mid-flight: a plan exists and at least one segment is partly done.
    rows = await Segment.filter(task_id=task.id)
    assert len(rows) == 4
    assert await Segment.filter(task_id=task.id, downloaded__gt=0).count() > 0

    # Whatever a watermark claims must actually be on disk.
    part = tmp_path / str(task.id) / "file.part"
    written = part.read_bytes()
    for row in rows:
        end = row.start_byte + row.downloaded
        assert written[row.start_byte : end] == BIG[row.start_byte : end]

    already = sum(row.downloaded for row in rows)
    assert already > 0

    # Now finish, with a brand new worker and a brand new control.
    await Task.filter(id=task.id).update(status=TaskStatus.PENDING)
    seen: list[str] = []
    async with httpx.AsyncClient(transport=httpx.MockTransport(_ranged(seen=seen))) as client:
        worker = _worker(tmp_path, DownloadControl(), client, segments=4)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE
    assert (tmp_path / str(task.id) / "f.bin").read_bytes() == BIG

    # And it genuinely resumed: the bytes already on disk were not re-fetched.
    refetched = 0
    for header in seen:
        start_text, _, end_text = header.removeprefix("bytes=").partition("-")
        if end_text and int(end_text) - int(start_text) + 1 > 1:
            refetched += int(end_text) - int(start_text) + 1
    assert refetched == len(BIG) - already, f"re-downloaded {refetched} of {len(BIG)}"


async def test_cancel_removes_the_segment_rows(db: None, tmp_path: Path) -> None:
    from src.service.download import DownloadService

    task = await _direct_task()
    await SegmentDatabaseRepo().reconcile(task.id, "file", [(0, 0, 99), (1, 100, 199)])

    service = DownloadService(
        repo=TaskDatabaseRepo(),
        segment_repo=SegmentDatabaseRepo(),
        client=FakeYouTube(),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=tmp_path,
    )
    await service.cancel(task.id)

    assert await Segment.filter(task_id=task.id).count() == 0


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


async def test_cancelling_mid_download_leaves_no_files_behind(db: None, tmp_path: Path) -> None:
    # The leak this guards: cancel removes the task directory, but the running
    # download recreates it on its next open, leaving an orphaned .part.
    task = await _task()
    control = DownloadControl()
    control.request_stop(task.id)
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, control, client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        # Cancel wins the race: the row is CANCELED before the worker unwinds.
        claimed.status = TaskStatus.CANCELED
        await claimed.save(update_fields=["status"])
        await worker.run_task(claimed)

    assert not (tmp_path / str(task.id)).exists()


async def test_pausing_mid_download_keeps_the_partial_file(db: None, tmp_path: Path) -> None:
    # The mirror of the above: a pause must NOT sweep the files, since the
    # whole point is resuming from them.
    task = await _task()
    control = DownloadControl()
    control.request_stop(task.id)
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, control, client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        claimed.status = TaskStatus.PAUSED
        await claimed.save(update_fields=["status"])
        await worker.run_task(claimed)

    assert (tmp_path / str(task.id) / "video.part").exists()
