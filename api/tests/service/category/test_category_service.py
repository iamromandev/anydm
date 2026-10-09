import uuid
from pathlib import Path

import pytest
from src.core.error import Error
from src.data.type import DOWNLOADS_ID
from src.service.category.category_service import CategoryService

from .fake_category_repo import FakeCategoryRepo


def _service(tmp_path: Path, repo: FakeCategoryRepo | None = None) -> CategoryService:
    return CategoryService(repo or FakeCategoryRepo(), tmp_path)


async def _refused(call, code: int, message: str) -> None:
    with pytest.raises(Error) as raised:
        await call
    assert int(raised.value.code) == code
    assert raised.value.message == message


@pytest.mark.asyncio
async def test_the_list_is_in_position_order_with_counts(tmp_path: Path) -> None:
    repo = FakeCategoryRepo()
    repo.counts = {DOWNLOADS_ID: 3}
    listed = (await _service(tmp_path, repo).list()).categories
    assert listed[0].name == "Downloads" and listed[0].count == 3 and listed[0].builtin
    assert listed[1].count == 0


@pytest.mark.asyncio
async def test_create_derives_the_slug_and_normalises_the_folder(tmp_path: Path) -> None:
    made = await _service(tmp_path).create("Edu Lectures", "edu/./lectures/")
    assert (made.slug, made.folder, made.builtin) == ("edu_lectures", "edu/lectures", False)


@pytest.mark.asyncio
@pytest.mark.parametrize("folder", ["../x", "/etc", "a/../../x"])
async def test_a_folder_outside_the_download_folder_is_refused(tmp_path: Path, folder: str) -> None:
    await _refused(
        _service(tmp_path).create("Escape", folder), 400, f"Folder {folder!r} is outside the download folder"
    )


@pytest.mark.asyncio
async def test_names_are_required_meaningful_and_unique(tmp_path: Path) -> None:
    service = _service(tmp_path)
    await _refused(service.create("  ", ""), 422, "A category needs a name")
    await _refused(service.create("!!", ""), 422, "A category name needs a letter or a digit")
    await _refused(service.create("videos", ""), 409, "There is already a category named videos")


@pytest.mark.asyncio
async def test_rename_keeps_the_folder_and_renaming_to_itself_is_allowed(tmp_path: Path) -> None:
    repo = FakeCategoryRepo()
    videos = repo.named("videos")
    service = _service(tmp_path, repo)
    renamed = await service.update(videos.id, "Clips", None)
    assert (renamed.name, renamed.slug, renamed.folder) == ("Clips", "clips", "videos")
    assert (await service.update(videos.id, "clips", None)).name == "clips"


@pytest.mark.asyncio
async def test_downloads_keeps_its_folder_and_cannot_be_deleted_but_can_be_renamed(tmp_path: Path) -> None:
    service = _service(tmp_path)
    message = "Downloads is built in: its folder can't change and it can't be deleted"
    await _refused(service.update(DOWNLOADS_ID, None, "elsewhere"), 422, message)
    await _refused(service.delete(DOWNLOADS_ID), 422, message)
    assert (await service.update(DOWNLOADS_ID, "Inbox", None)).name == "Inbox"


@pytest.mark.asyncio
async def test_an_unknown_id_is_not_found(tmp_path: Path) -> None:
    missing = uuid.uuid4()
    await _refused(_service(tmp_path).update(missing, "x", None), 404, f"There is no category with id {missing}")
    await _refused(_service(tmp_path).delete(missing), 404, f"There is no category with id {missing}")


@pytest.mark.asyncio
async def test_order_needs_every_category_once(tmp_path: Path) -> None:
    repo = FakeCategoryRepo()
    service = _service(tmp_path, repo)
    ids = [row.id for row in await repo.list_all()]
    await _refused(service.order(ids[1:]), 422, "List every category exactly once")
    await _refused(service.order([ids[0], *ids]), 422, "List every category exactly once")
    listed = await service.order(list(reversed(ids)))
    assert [c.id for c in listed.categories] == list(reversed(ids))


@pytest.mark.asyncio
async def test_a_category_in_use_cannot_be_deleted(tmp_path: Path) -> None:
    repo = FakeCategoryRepo()
    music = repo.named("music")
    repo.counts = {music.id: 1}
    service = _service(tmp_path, repo)
    await _refused(service.delete(music.id), 409, "Music still holds 1 download; move them first")
    repo.counts = {music.id: 2}
    await _refused(service.delete(music.id), 409, "Music still holds 2 downloads; move them first")
    repo.counts = {}
    await service.delete(music.id)
    assert music.id in repo.deleted
