"""``PieceDatabaseRepo`` against Postgres: a torrent's piece list is written once, a download's bitmap is one value."""

import pytest
from src.core.common import now
from src.data.db.model import Download, Mirror, Piece
from src.data.repo import PieceDatabaseRepo

from tests.integration.rows import a_torrent_download

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

HASH = "e" * 40


@pytest.mark.asyncio
async def test_ensure_writes_the_list_once_and_lists_it_in_order() -> None:
    repo, row = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    await repo.ensure(row.id, [(1, 100, "b" * 40), (0, 512, "a" * 40)])
    await repo.ensure(row.id, [(0, 1, "c" * 40)])

    assert [(p.index, p.size, p.hash) for p in await repo.list_for(row.id)] == [
        (0, 512, "a" * 40),
        (1, 100, "b" * 40),
    ]


@pytest.mark.asyncio
async def test_the_bitmap_is_none_until_saved_then_one_value_that_is_replaced() -> None:
    repo, row = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    assert await repo.bitmap(row.id) is None

    await repo.save_bitmap(row.id, b"\xa0")
    assert await repo.bitmap(row.id) == b"\xa0"
    await repo.save_bitmap(row.id, b"\xe0")
    assert await repo.bitmap(row.id) == b"\xe0"
    assert (await Download.get(id=row.id)).pieces == b"\xe0"


@pytest.mark.asyncio
async def test_a_download_without_a_torrent_has_nothing() -> None:
    from uuid import uuid4

    repo = PieceDatabaseRepo()
    assert await repo.list_for(uuid4()) == []
    await repo.ensure(uuid4(), [(0, 1, "a" * 40)])
    assert await repo.bitmap(uuid4()) is None
    assert await Piece.all().count() == 0


@pytest.mark.asyncio
async def test_a_second_download_of_the_torrent_shares_the_list_but_not_the_bitmap() -> None:
    repo, first = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    await repo.ensure(first.id, [(0, 512, "a" * 40), (1, 512, "b" * 40)])
    await repo.save_bitmap(first.id, b"\x80")
    await Download.filter(id=first.id).update(deleted_at=now())

    # The same torrent again, through the same source: a new download with its own mirror.
    source = (await Mirror.get(download_id=first.id).prefetch_related("source")).source
    second = await Download.create(status="pending")
    await Mirror.create(download=second, source=source)

    assert [p.index for p in await repo.list_for(second.id)] == [0, 1]
    assert await repo.bitmap(second.id) is None
