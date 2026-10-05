import pytest
from src.data.db.model import Segment
from src.data.repo import SegmentDatabaseRepo
from src.data.type import SegmentPart, SegmentStatus

from tests.integration.rows import a_download, a_site_download

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

PLAN = [(0, 0, 249), (1, 250, 499), (2, 500, 749), (3, 750, 999)]
FILE, VIDEO, AUDIO = SegmentPart.FILE, SegmentPart.VIDEO, SegmentPart.AUDIO


async def test_a_first_reconcile_creates_the_plan_and_reports_fresh(db: None) -> None:
    download = await a_download(filename="a.bin")
    result = await SegmentDatabaseRepo().reconcile(download.id, FILE, PLAN)
    assert result.fresh is True
    assert result.watermarks == {0: 0, 1: 0, 2: 0, 3: 0}
    assert await Segment.filter(file__download_id=download.id).count() == 4


async def test_a_matching_reconcile_returns_the_saved_watermarks(db: None) -> None:
    download = await a_download(filename="a.bin")
    repo = SegmentDatabaseRepo()
    await repo.reconcile(download.id, FILE, PLAN)
    await repo.flush(download.id, FILE, {0: 100, 2: 40})

    result = await repo.reconcile(download.id, FILE, PLAN)
    assert result.fresh is False
    assert result.watermarks == {0: 100, 1: 0, 2: 40, 3: 0}
    statuses = (
        await Segment.filter(file__download_id=download.id).order_by("start_byte").values_list("status", flat=True)
    )
    assert statuses == [
        SegmentStatus.DOWNLOADING,
        SegmentStatus.PENDING,
        SegmentStatus.DOWNLOADING,
        SegmentStatus.PENDING,
    ]
    await repo.flush(download.id, FILE, {1: 250})
    assert (await Segment.get(file__download_id=download.id, start_byte=250)).status == SegmentStatus.COMPLETED


async def test_a_changed_plan_discards_the_old_rows(db: None) -> None:
    """DOWNLOAD_SEGMENTS changed, or the server reports a different size — as a
    replaced address or a mirror of another size does. The old ranges no longer
    describe what is on disk."""
    download = await a_download(filename="a.bin")
    repo = SegmentDatabaseRepo()
    await repo.reconcile(download.id, FILE, PLAN)
    await repo.flush(download.id, FILE, {0: 100})

    result = await repo.reconcile(download.id, FILE, [(0, 0, 499), (1, 500, 999)])
    assert result.fresh is True
    assert result.watermarks == {0: 0, 1: 0}
    assert await Segment.filter(file__download_id=download.id).count() == 2


async def test_parts_of_one_download_do_not_collide(db: None) -> None:
    """A site download has two independent segment sets."""
    download = await a_site_download("vvvvvvvvvvv", filename="clip.mp4")
    repo = SegmentDatabaseRepo()
    await repo.reconcile(download.id, VIDEO, PLAN)
    await repo.reconcile(download.id, AUDIO, PLAN)
    await repo.flush(download.id, VIDEO, {0: 7})

    assert (await repo.reconcile(download.id, VIDEO, PLAN)).watermarks[0] == 7
    assert (await repo.reconcile(download.id, AUDIO, PLAN)).watermarks[0] == 0


async def test_clear_removes_one_part_or_all_of_them(db: None) -> None:
    download = await a_download(filename="a.bin")
    repo = SegmentDatabaseRepo()
    await repo.reconcile(download.id, VIDEO, PLAN)
    await repo.reconcile(download.id, AUDIO, PLAN)

    await repo.clear(download.id, VIDEO)
    assert await Segment.filter(file__download_id=download.id).count() == 4

    await repo.clear(download.id)
    assert await Segment.filter(file__download_id=download.id).count() == 0
