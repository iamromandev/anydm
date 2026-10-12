import uuid

import pytest
from src.core.error import Error
from src.data.repo.category import DOWNLOADS
from src.data.type import DOWNLOADS_ID
from src.service.category.pick import pick_category, pick_or_match

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


@pytest.mark.asyncio
async def test_an_explicit_choice_wins_over_what_the_filename_says() -> None:
    repo = FakeCategoryRepo()

    row = await pick_or_match(repo, repo.named("music").id, filename="disc.iso")

    assert row.slug == "music"


@pytest.mark.asyncio
async def test_choosing_downloads_on_purpose_wins_too() -> None:
    row = await pick_or_match(FakeCategoryRepo(), DOWNLOADS_ID, filename="disc.iso")

    assert row.id == DOWNLOADS_ID


@pytest.mark.asyncio
async def test_without_a_choice_the_extension_files_it() -> None:
    row = await pick_or_match(FakeCategoryRepo(), None, filename="ubuntu.iso")

    assert row.slug == "software"


@pytest.mark.asyncio
async def test_an_unknown_extension_stays_in_downloads() -> None:
    row = await pick_or_match(FakeCategoryRepo(), None, filename="notes.xyz")

    assert row.id == DOWNLOADS_ID


@pytest.mark.asyncio
async def test_an_audio_only_download_goes_to_music_whatever_its_extension() -> None:
    row = await pick_or_match(FakeCategoryRepo(), None, filename="clip.webm", audio_only=True)

    assert row.slug == "music"


@pytest.mark.asyncio
async def test_a_deleted_music_category_leaves_audio_to_its_extension() -> None:
    repo = FakeCategoryRepo()
    await repo.delete(repo.named("music").id)

    row = await pick_or_match(repo, None, filename="clip.mp3", audio_only=True)

    assert row.id == DOWNLOADS_ID


@pytest.mark.asyncio
async def test_an_unknown_choice_is_refused() -> None:
    with pytest.raises(Error):
        await pick_or_match(FakeCategoryRepo(), uuid.uuid4(), filename="a.iso")


@pytest.mark.asyncio
async def test_without_a_repository_only_downloads_exists() -> None:
    row = await pick_or_match(None, None, filename="a.iso")

    assert row.id == DOWNLOADS_ID
