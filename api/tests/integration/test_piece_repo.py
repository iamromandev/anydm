"""``PieceDatabaseRepo`` against Postgres: batched replace, listing, and the one batched completion write."""

import pytest
from src.data.repo import PieceDatabaseRepo
from src.data.type.torrent.piece import PieceStatus

from tests.integration.rows import a_torrent_download

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

HASH = "e" * 40


@pytest.mark.asyncio
async def test_replace_is_idempotent_and_ordered() -> None:
    repo, row = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    pieces = [(1, 100, "b" * 40), (0, 512, "a" * 40)]
    await repo.replace(row.id, pieces)
    await repo.replace(row.id, pieces)
    assert [(p.index, p.size, p.hash) for p in await repo.list_for(row.id)] == [
        (0, 512, "a" * 40),
        (1, 100, "b" * 40),
    ]
    assert [p.status for p in await repo.list_for(row.id)] == [PieceStatus.PENDING, PieceStatus.PENDING]


@pytest.mark.asyncio
async def test_completed_pieces_count_their_full_size() -> None:
    repo, row = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    await repo.replace(row.id, [(0, 512, "a" * 40), (1, 512, "b" * 40), (2, 88, "c" * 40)])

    await repo.complete(row.id, [2, 0])

    finished = {p.index: p for p in await repo.list_for(row.id)}
    assert (finished[0].status, finished[0].downloaded_bytes) == (PieceStatus.COMPLETED, 512)
    assert (finished[2].status, finished[2].downloaded_bytes) == (PieceStatus.COMPLETED, 88)
    assert finished[1].status == PieceStatus.PENDING
    assert [p.index for p in await repo.list_for(row.id) if p.status == PieceStatus.COMPLETED] == [0, 2]


@pytest.mark.asyncio
async def test_a_download_without_pieces_reads_empty() -> None:
    repo, row = PieceDatabaseRepo(), await a_torrent_download(HASH, name="Release")
    assert await repo.list_for(row.id) == []
    await repo.complete(row.id, [0])
