import uuid

import pytest
from src.core.common import now
from src.data.db.model import Download
from src.data.repo import CategoryDatabaseRepo
from src.data.type import DOWNLOADS_ID, DownloadStatus

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_create_places_last_and_list_reads_by_position() -> None:
    repo = CategoryDatabaseRepo()
    made = await repo.create("Lectures", "lectures", "edu/lectures")
    listed = await repo.list_all()
    assert listed[0].id == DOWNLOADS_ID and listed[-1] == made
    assert made.position == len(listed) - 1 and made.builtin is False


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_update_changes_only_what_is_given_and_by_name_ignores_case() -> None:
    repo = CategoryDatabaseRepo()
    made = await repo.create("Lectures", "lectures", "edu/lectures")
    changed = await repo.update(made.id, folder="edu/talks")
    assert changed is not None and (changed.name, changed.folder) == ("Lectures", "edu/talks")
    assert await repo.by_name("LECTURES") == changed
    assert await repo.update(uuid.UUID(int=0), name="x") is None


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_reorder_sets_every_position() -> None:
    repo = CategoryDatabaseRepo()
    ids = [row.id for row in await repo.list_all()]
    await repo.reorder(list(reversed(ids)))
    assert [row.id for row in await repo.list_all()] == list(reversed(ids))


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_usage_counts_list_items_not_collection_videos_or_removed_rows() -> None:
    repo = CategoryDatabaseRepo()
    made = await repo.create("Lectures", "lectures", "edu/lectures")
    await Download.create(category_id=made.id, status=DownloadStatus.PENDING)
    await Download.create(category_id=made.id, status=DownloadStatus.CANCELLED, deleted_at=now())
    assert (await repo.usage())[made.id] == 1


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_delete_moves_removed_rows_to_downloads_and_keeps_live_ones_in_place() -> None:
    repo = CategoryDatabaseRepo()
    made = await repo.create("Lectures", "lectures", "edu/lectures")
    live = await Download.create(category_id=made.id, status=DownloadStatus.PENDING)
    gone = await Download.create(category_id=made.id, status=DownloadStatus.CANCELLED, deleted_at=now())

    assert await repo.delete(made.id) is True

    await live.refresh_from_db()
    await gone.refresh_from_db()
    assert live.category_id == DOWNLOADS_ID and gone.category_id == DOWNLOADS_ID
    assert await repo.get(made.id) is None


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_set_category_moves_one_download_and_writes_its_folder_only_when_given() -> None:
    from src.data.repo import DownloadDatabaseRepo

    made = await CategoryDatabaseRepo().create("Lectures", "lectures", "edu")
    repo = DownloadDatabaseRepo()
    row = await repo.create_direct(
        url="https://cdn.test/a.bin", download={"status": DownloadStatus.COMPLETED}, filename="a.bin"
    )

    await repo.set_category(row.id, made.id, "edu")
    await row.refresh_from_db()
    assert (row.category_id, row.folder) == (made.id, "edu")

    await repo.set_category(row.id, DOWNLOADS_ID, None)
    await row.refresh_from_db()
    assert (row.category_id, row.folder) == (DOWNLOADS_ID, "edu")


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_move_rows_moves_a_collection_and_the_videos_that_have_a_place() -> None:
    from src.data.repo import CollectionDatabaseRepo

    from tests.integration.rows import a_collection, a_site_download

    made = await CategoryDatabaseRepo().create("Lectures", "lectures", "edu")
    collection = await a_collection()
    done = await a_site_download("done", parent=collection, status=DownloadStatus.COMPLETED)
    waiting = await a_site_download("waiting", parent=collection, status=DownloadStatus.PAUSED)

    await CollectionDatabaseRepo().move_rows(collection.id, made.id, "edu/talks")

    await collection.refresh_from_db()
    await done.refresh_from_db()
    await waiting.refresh_from_db()
    assert (collection.category_id, collection.folder) == (made.id, "edu/talks")
    assert (done.category_id, done.folder) == (made.id, "edu/talks")
    # Not finished, so it has no place yet: it takes the category and finds its folder when it does.
    assert (waiting.category_id, waiting.folder) == (made.id, None)


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_seed_adds_only_the_slugs_not_stored_and_leaves_the_rest() -> None:
    repo = CategoryDatabaseRepo()
    games = await repo.by_name("games")
    assert games is not None
    await repo.delete(games.id)
    movies = await repo.by_name("movies")
    assert movies is not None
    await repo.update(movies.id, folder="cinema")

    rows = await repo.list_all()
    again = [*rows, games]
    assert await repo.seed(again) == 1
    assert await repo.seed(again) == 0

    assert await repo.get(games.id) == games
    kept = await repo.get(movies.id)
    assert kept is not None and kept.folder == "cinema"
