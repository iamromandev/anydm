import uuid
from pathlib import Path

import pytest
from src.data.type import DownloadStatus, SegmentPart
from src.service.download.live import Live, LiveStats
from src.service.download.progress import AggregateSample, SegmentProgress

from tests.service.download.memory import MemoryFiles, RecordingHub
from tests.service.download.workers import (
    FakeCollections,
    FakeSegmentRepo,
    FlushRecordingRepo,
    site_row,
    worker,
)


def test_the_count_halves_on_every_attempt_of_a_fresh_part(tmp_path: Path) -> None:
    """A server that 429s under four connections is fine with one."""
    w = worker(tmp_path)
    assert [w._segment_count(n, 0) for n in (1, 2, 3, 9)] == [4, 2, 1, 1]


def test_bytes_on_disk_outrank_the_backoff(tmp_path: Path) -> None:
    """Crash recovery bumps `attempts` without any failure having happened.

    Backing off there would plan a different set of ranges, which reconcile
    cannot match, so the whole partial `.part` would be thrown away.
    """
    w = worker(tmp_path)
    assert w._segment_count(2, 2496) == 4
    assert w._segment_count(9, 1) == 4


def _sample(segments: tuple[SegmentProgress, ...] = ()) -> AggregateSample:
    return AggregateSample(
        downloaded_bytes=300, total_bytes=1000, progress=30, speed_bps=99, eta_seconds=7, segments=segments
    )


@pytest.mark.asyncio
async def test_a_tick_writes_bytes_and_sends_live_numbers_in_a_frame(tmp_path: Path) -> None:
    repo, hub, live, segments = FlushRecordingRepo(), RecordingHub(), LiveStats(), FakeSegmentRepo()
    w = worker(tmp_path, repo=repo, hub=hub, live=live, segment_repo=segments)
    download_id = uuid.uuid4()

    await w._flush(
        download_id,
        SegmentPart.FILE,
        _sample((SegmentProgress(0, 0, 499, 200, 60), SegmentProgress(1, 500, 999, 100, 39))),
        offset=0,
        total=1000,
    )

    assert repo.flushed == [{"downloaded_bytes": 300, "total_bytes": 1000}]
    assert live.get(download_id) == Live(speed_bps=99, eta_seconds=7)
    (frame,) = hub.named("progress")
    assert frame["progress"] == 30
    assert frame["live"]["speed_bps"] == 99
    assert frame["segments"] == [
        {"index": 0, "start": 0, "end": 499, "downloaded": 200, "speed_bps": 60},
        {"index": 1, "start": 500, "end": 999, "downloaded": 100, "speed_bps": 39},
    ]
    assert segments.flushed == [("file", {0: 200, 1: 100})]


@pytest.mark.asyncio
async def test_an_unsegmented_flush_omits_the_key_entirely(tmp_path: Path) -> None:
    """Absent, not empty: the UI reads a missing key as 'nothing changed'."""
    hub, segments = RecordingHub(), FakeSegmentRepo()

    await worker(tmp_path, hub=hub, segment_repo=segments)._flush(
        uuid.uuid4(), SegmentPart.FILE, _sample(), offset=0, total=1000
    )

    (frame,) = hub.named("progress")
    assert "segments" not in frame
    assert "collection_id" not in frame
    assert segments.flushed == []


@pytest.mark.asyncio
async def test_a_collection_video_flush_names_its_collection(tmp_path: Path) -> None:
    """The browser routes it to the collection's row, never the list (v0.5)."""
    hub = RecordingHub()
    collection_id = uuid.uuid4()

    await worker(tmp_path, hub=hub)._flush(
        uuid.uuid4(), SegmentPart.VIDEO, _sample(), offset=0, total=1000, collection_id=collection_id
    )

    assert hub.named("progress")[0]["collection_id"] == str(collection_id)


@pytest.mark.asyncio
async def test_the_offset_of_an_earlier_part_still_shifts_the_totals(tmp_path: Path) -> None:
    hub = RecordingHub()

    await worker(tmp_path, hub=hub)._flush(
        uuid.uuid4(), SegmentPart.AUDIO, _sample((SegmentProgress(0, 0, 999, 300, 99),)), offset=2000, total=3000
    )

    frame = hub.named("progress")[0]
    assert frame["downloaded_bytes"] == 2300
    assert frame["progress"] == 76


@pytest.mark.asyncio
async def test_completing_a_collection_video_moves_it_and_records_the_folder(tmp_path: Path) -> None:
    files, live, collections = MemoryFiles(), LiveStats(), FakeCollections("Talks")
    row = await site_row(files, filename="01_Talk.mp4", collection_id=collections.collection.id)
    live.set(row.id, Live(speed_bps=3))
    w = worker(tmp_path, files=files, live=live, collections=collections)
    work = tmp_path / str(row.id)
    work.mkdir()
    (work / "01_Talk.mp4").write_bytes(b"12345")

    destination, folder = await w._into_folder(row, work / "01_Talk.mp4")
    await w._mark_complete(row, destination, folder)

    assert (tmp_path / "Talks" / "01_Talk.mp4").read_bytes() == b"12345"
    assert not work.exists()
    assert (row.status, row.folder, row.total_bytes) == (DownloadStatus.COMPLETE, "Talks", 5)
    single = await files.single(row.id)
    assert single is not None and (single.path, single.size_bytes) == ("01_Talk.mp4", 5)
    assert live.get(row.id) == Live()


@pytest.mark.asyncio
async def test_a_standalone_download_finishes_where_it_was_worked_on(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await site_row(files)
    (tmp_path / str(row.id)).mkdir()
    file = tmp_path / str(row.id) / "a.bin"
    file.write_bytes(b"x")

    destination, folder = await worker(tmp_path, files=files)._into_folder(row, file)

    assert (destination, folder) == (file, str(row.id))
