"""A direct download nobody chose a category for is filed by the Content-Type its server sends (#174)."""

from pathlib import Path

import pytest
from src.data.type import DOWNLOADS_ID

from tests.service.category.fake_category_repo import FakeCategoryRepo
from tests.service.download.memory import MemoryFiles, RecordingHub
from tests.service.download.test_worker_disk import FakeEngine, FinishingPostProcessor
from tests.service.download.workers import FlushRecordingRepo, direct_row, worker

pytestmark = pytest.mark.asyncio


async def _run(
    tmp_path: Path, *, content_type: str | None, **row: object
) -> tuple[FlushRecordingRepo, object, FakeCategoryRepo]:
    files, repo, categories = MemoryFiles(), FlushRecordingRepo(), FakeCategoryRepo()
    download = await direct_row(files, **row)
    engine = FakeEngine(total=10, content_type=content_type)
    await worker(
        tmp_path,
        files=files,
        repo=repo,
        engine=engine,
        hub=RecordingHub(),
        post=FinishingPostProcessor(),
        categories=categories,
    ).run_task(download)
    return repo, download, categories


async def test_an_automatic_download_in_downloads_is_filed_by_the_content_type(tmp_path: Path) -> None:
    repo, download, categories = await _run(tmp_path, content_type="video/mp4", category_auto=True)

    assert repo.categorized == [(download.id, categories.named("videos").id)]  # ty: ignore[unresolved-attribute]


async def test_a_chosen_category_is_left_alone(tmp_path: Path) -> None:
    repo, _, _ = await _run(tmp_path, content_type="video/mp4", category_auto=False)

    assert repo.categorized == []


async def test_a_download_already_filed_by_its_name_is_left_alone(tmp_path: Path) -> None:
    categories = FakeCategoryRepo()
    repo, _, _ = await _run(
        tmp_path, content_type="video/mp4", category_auto=True, category_id=categories.named("software").id
    )

    assert repo.categorized == []


async def test_a_content_type_that_names_no_category_changes_nothing(tmp_path: Path) -> None:
    repo, download, _ = await _run(tmp_path, content_type="application/octet-stream", category_auto=True)

    assert repo.categorized == []
    assert download.category_id == DOWNLOADS_ID  # ty: ignore[unresolved-attribute]


async def test_a_server_that_sends_no_content_type_changes_nothing(tmp_path: Path) -> None:
    repo, _, _ = await _run(tmp_path, content_type=None, category_auto=True)

    assert repo.categorized == []
