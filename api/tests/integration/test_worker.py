from pathlib import Path
from typing import Any

import httpx
import pytest
from src.data.db.model import Download, Segment
from src.data.repo import (
    CollectionDatabaseRepo,
    DownloadDatabaseRepo,
    FileDatabaseRepo,
    MirrorDatabaseRepo,
    PositionDatabaseRepo,
    SegmentDatabaseRepo,
)
from src.data.type import DownloadStatus, MediaKind, Platform, Preset, SegmentPart
from src.lib.event import EventHub
from src.lib.site.client import Resolved
from src.service.download.collection_service import CollectionService
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.download_service import DownloadService
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Downloader
from src.service.download.live import LiveStats
from src.service.download.post_process import FfmpegPostProcessor
from src.service.download.segmented import SegmentedDownloader
from src.service.download.views import DownloadViews

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

BODY = b"z" * 500


class FakeSite:
    """Resolves any format id to a URL on the mock transport's host."""

    async def extract(self, url: str) -> Any:
        raise AssertionError("the worker must not re-extract the page")

    async def resolve(self, url: str, format_ids: Any) -> dict[str, Resolved]:
        return {format_id: Resolved(f"https://cdn.test/x/{format_id}") for format_id in format_ids}

    async def open(self, url: str) -> Any:
        raise AssertionError("these downloads are planned already")


def _views(live: LiveStats) -> DownloadViews:
    return DownloadViews(
        files=FileDatabaseRepo(),
        positions=PositionDatabaseRepo(),
        mirrors=MirrorDatabaseRepo(),
        live=live,
        max_attempts=3,
    )


def _worker(tmp_path: Path, control: DownloadControl, client: httpx.AsyncClient, segments: int = 1) -> DownloadWorker:
    """``segments=1`` by default, so the single-stream path stays exercised."""
    downloader = Downloader(client, chunk_size=64, flush_interval_ms=0)
    live = LiveStats()
    return DownloadWorker(
        name="test-worker",
        repo=DownloadDatabaseRepo(),
        segment_repo=SegmentDatabaseRepo(),
        files=FileDatabaseRepo(),
        collections=CollectionDatabaseRepo(),
        client=FakeSite(),  # ty: ignore[invalid-argument-type]
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
        live=live,
        views=_views(live),
    )


async def _site(*, attempts: int = 0, total_bytes: int | None = None, **site: Any) -> Download:
    detail: dict[str, Any] = {"extractor": "Youtube", "video_id": "x", "preset": Preset.P720, "video_format": "22"}
    detail.update(site)
    row = await DownloadDatabaseRepo().create_site(
        {
            "source_url": "https://youtu.be/x",
            "platform": Platform.SITE,
            "media_kind": MediaKind.VIDEO,
            "status": DownloadStatus.PENDING,
            "title": "clip",
            "total_bytes": total_bytes,
        },
        detail,
        "clip.mp4",
        "video/mp4",
    )
    if attempts:
        await Download.filter(id=row.id).update(attempts=attempts)
    return row


async def _direct(filename: str = "f.bin") -> Download:
    return await DownloadDatabaseRepo().create_direct(
        {
            "source_url": f"https://cdn.test/{filename}",
            "platform": Platform.DIRECT,
            "media_kind": MediaKind.FILE,
            "status": DownloadStatus.PENDING,
            "title": filename,
        },
        filename,
    )


async def _run(tmp_path: Path, control: DownloadControl, transport: httpx.MockTransport, segments: int = 1) -> None:
    async with httpx.AsyncClient(transport=transport) as client:
        claimed = await DownloadDatabaseRepo().claim_next()
        assert claimed is not None
        await _worker(tmp_path, control, client, segments).run_task(claimed)


async def test_a_claimed_download_completes_and_records_its_file(db: None, tmp_path: Path) -> None:
    row = await _site()

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(lambda request: httpx.Response(200, content=BODY)))

    await row.refresh_from_db()
    assert (row.status, row.folder, row.downloaded_bytes, row.total_bytes) == (
        DownloadStatus.COMPLETE,
        str(row.id),
        500,
        500,
    )
    single = await FileDatabaseRepo().single(row.id)
    assert single is not None and (single.path, single.size_bytes) == ("clip.mp4", 500)
    assert (tmp_path / str(row.id) / "clip.mp4").read_bytes() == BODY


async def test_a_retryable_failure_requeues_with_a_backoff(db: None, tmp_path: Path) -> None:
    row = await _site()

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(lambda request: httpx.Response(503)))

    await row.refresh_from_db()
    assert (row.status, row.attempts) == (DownloadStatus.PENDING, 1)
    assert row.next_attempt_at is not None
    assert row.error is not None


async def test_a_permanent_failure_stops_at_once(db: None, tmp_path: Path) -> None:
    row = await _site()

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(lambda request: httpx.Response(404)))

    await row.refresh_from_db()
    assert row.status == DownloadStatus.FAILED
    assert row.next_attempt_at is None


async def test_the_last_attempt_fails_rather_than_retrying(db: None, tmp_path: Path) -> None:
    row = await _site(attempts=2)

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(lambda request: httpx.Response(503)))

    await row.refresh_from_db()
    assert (row.attempts, row.status) == (3, DownloadStatus.FAILED)


async def test_a_stop_request_leaves_the_partial_file(db: None, tmp_path: Path) -> None:
    row = await _site()
    control = DownloadControl()
    control.request_stop(row.id)

    await _run(tmp_path, control, httpx.MockTransport(lambda request: httpx.Response(200, content=BODY)))

    assert (tmp_path / str(row.id) / "video.part").exists()


async def test_a_resumed_download_continues_from_the_partial_file(db: None, tmp_path: Path) -> None:
    row = await _site()
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("range"))
        return httpx.Response(206, content=BODY[100:], headers={"content-range": f"bytes 100-499/{len(BODY)}"})

    part = tmp_path / str(row.id) / "video.part"
    part.parent.mkdir(parents=True)
    part.write_bytes(BODY[:100])

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(handler))

    # The one-byte probe precedes every transfer; what matters is that the
    # transfer itself still resumes from the partial file rather than restarting.
    assert seen == ["bytes=0-0", "bytes=100-"]
    await row.refresh_from_db()
    assert row.status == DownloadStatus.COMPLETE
    assert (tmp_path / str(row.id) / "clip.mp4").read_bytes() == BODY


async def test_a_direct_download_fetches_from_its_source_url(db: None, tmp_path: Path) -> None:
    row = await _direct("file.bin")
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(str(request.url))
        return httpx.Response(200, content=BODY)

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(handler))

    # Every transfer is preceded by a one-byte range probe; both go to the source URL.
    assert requested == ["https://cdn.test/file.bin", "https://cdn.test/file.bin"]
    await row.refresh_from_db()
    assert row.status == DownloadStatus.COMPLETE


BIG = bytes(range(256)) * 64  # 16384 bytes


def _ranged(body: bytes = BIG, seen: list[str] | None = None) -> Any:
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
            headers={"content-range": f"bytes {start}-{end}/{len(body)}", "content-length": str(len(chunk))},
        )

    return handler


async def test_a_segmented_download_records_its_plan_and_clears_it_when_done(db: None, tmp_path: Path) -> None:
    row = await _direct()

    await _run(tmp_path, DownloadControl(), httpx.MockTransport(_ranged()), segments=4)

    await row.refresh_from_db()
    assert row.status == DownloadStatus.COMPLETE
    assert (tmp_path / str(row.id) / "f.bin").read_bytes() == BIG
    # Transient state: gone once the file exists.
    assert await Segment.filter(download_id=row.id).count() == 0


async def test_an_interrupted_download_finishes_from_the_database_alone(db: None, tmp_path: Path) -> None:
    """The test that proves the ordering rule.

    Stop mid-transfer, throw away every in-memory object, rebuild from the rows
    and the .part on disk, and finish. A watermark that ever ran ahead of the
    disk produces a file of the right size and the wrong bytes here.
    """

    class StopsPartWay(DownloadControl):
        """Let some bytes land before pulling the plug, so there is something to compare."""

        def __init__(self, after: int) -> None:
            super().__init__()
            self._left = after

        def is_stopping(self, download_id: Any) -> bool:
            self._left -= 1
            return self._left <= 0

    row = await _direct()

    await _run(tmp_path, StopsPartWay(40), httpx.MockTransport(_ranged()), segments=4)

    # Mid-flight: a plan exists and at least one segment is partly done.
    rows = await Segment.filter(download_id=row.id)
    assert len(rows) == 4
    assert await Segment.filter(download_id=row.id, downloaded__gt=0).count() > 0

    # Whatever a watermark claims must actually be on disk.
    written = (tmp_path / str(row.id) / "file.part").read_bytes()
    for segment in rows:
        end = segment.start_byte + segment.downloaded
        assert written[segment.start_byte : end] == BIG[segment.start_byte : end]

    already = sum(segment.downloaded for segment in rows)
    assert already > 0

    # Now finish, with a brand new worker and a brand new control.
    await Download.filter(id=row.id).update(status=DownloadStatus.PENDING)
    seen: list[str] = []
    await _run(tmp_path, DownloadControl(), httpx.MockTransport(_ranged(seen=seen)), segments=4)

    await row.refresh_from_db()
    assert row.status == DownloadStatus.COMPLETE
    assert (tmp_path / str(row.id) / "f.bin").read_bytes() == BIG

    # And it genuinely resumed: the bytes already on disk were not re-fetched.
    refetched = 0
    for header in seen:
        start_text, _, end_text = header.removeprefix("bytes=").partition("-")
        if end_text and int(end_text) - int(start_text) + 1 > 1:
            refetched += int(end_text) - int(start_text) + 1
    assert refetched == len(BIG) - already, f"re-downloaded {refetched} of {len(BIG)}"


async def test_cancel_removes_the_segment_rows(db: None, tmp_path: Path) -> None:
    row = await _direct()
    await SegmentDatabaseRepo().reconcile(row.id, SegmentPart.FILE, [(0, 0, 99), (1, 100, 199)])
    live, hub, control = LiveStats(), EventHub(), DownloadControl()
    views = _views(live)
    totals = CollectionTotals(CollectionDatabaseRepo(), hub, live)
    service = DownloadService(
        repo=DownloadDatabaseRepo(),
        collections=CollectionService(
            repo=CollectionDatabaseRepo(),
            segment_repo=SegmentDatabaseRepo(),
            control=control,
            downloads_root=tmp_path,
            totals=totals,
            views=views,
        ),
        collection_repo=CollectionDatabaseRepo(),
        segment_repo=SegmentDatabaseRepo(),
        files=FileDatabaseRepo(),
        positions=PositionDatabaseRepo(),
        client=FakeSite(),  # ty: ignore[invalid-argument-type]
        control=control,
        hub=hub,
        downloads_root=tmp_path,
        torrents=None,  # ty: ignore[invalid-argument-type]  # this download is not a torrent
        views=views,
        totals=totals,
        live=live,
    )

    await service.cancel(row.id)

    assert await Segment.filter(download_id=row.id).count() == 0


async def test_progress_is_cumulative_across_a_two_part_download(db: None, tmp_path: Path) -> None:
    # The bug this guards: the audio part used to restart the percentage at
    # zero, so the UI ran 0-100 twice and appeared to go backwards.
    await _site(video_format="137", audio_format="140", total_bytes=1000)
    seen: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"y" * 500, headers={"content-length": "500"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        original = worker._repo.flush_progress

        async def spy(download_id: Any, **kwargs: Any) -> None:
            assert kwargs["total_bytes"] == 1000
            seen.append(kwargs["downloaded_bytes"])
            await original(download_id, **kwargs)

        worker._repo.flush_progress = spy  # ty: ignore[invalid-assignment]
        claimed = await DownloadDatabaseRepo().claim_next()
        assert claimed is not None
        await worker.run_task(claimed)

    assert seen == sorted(seen), f"bytes went backwards: {seen}"
    assert seen[-1] == 1000


async def test_cancelling_mid_download_leaves_no_files_behind(db: None, tmp_path: Path) -> None:
    # The leak this guards: cancel removes the work directory, but the running
    # download recreates it on its next open, leaving an orphaned .part.
    row = await _site()
    control = DownloadControl()
    control.request_stop(row.id)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))
    ) as client:
        claimed = await DownloadDatabaseRepo().claim_next()
        assert claimed is not None
        # Cancel wins the race: the row is CANCELED before the worker unwinds.
        await Download.filter(id=claimed.id).update(status=DownloadStatus.CANCELED)
        await _worker(tmp_path, control, client).run_task(claimed)

    assert not (tmp_path / str(row.id)).exists()


async def test_pausing_mid_download_keeps_the_partial_file(db: None, tmp_path: Path) -> None:
    # The mirror of the above: a pause must NOT sweep the files.
    row = await _site()
    control = DownloadControl()
    control.request_stop(row.id)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))
    ) as client:
        claimed = await DownloadDatabaseRepo().claim_next()
        assert claimed is not None
        await Download.filter(id=claimed.id).update(status=DownloadStatus.PAUSED)
        await _worker(tmp_path, control, client).run_task(claimed)

    assert (tmp_path / str(row.id) / "video.part").exists()
