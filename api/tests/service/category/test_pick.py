import uuid

import pytest
from src.core.error import Error
from src.data.repo.category import DOWNLOADS
from src.service.category.pick import pick_category

from .fake_category_repo import FakeCategoryRepo


@pytest.mark.asyncio
async def test_no_choice_is_downloads_with_or_without_a_repo() -> None:
    assert await pick_category(None, None) == DOWNLOADS
    assert (await pick_category(FakeCategoryRepo(), None)).slug == "downloads"


@pytest.mark.asyncio
async def test_a_chosen_category_is_read_and_an_unknown_one_is_422() -> None:
    repo = FakeCategoryRepo()
    music = repo.named("music")
    assert await pick_category(repo, music.id) == music
    missing = uuid.uuid4()
    with pytest.raises(Error) as raised:
        await pick_category(repo, missing)
    assert int(raised.value.code) == 422 and raised.value.message == f"There is no category with id {missing}"
