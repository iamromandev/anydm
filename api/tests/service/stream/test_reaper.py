import asyncio
from pathlib import Path

import pytest
from src.lib.media.source import MediaInput
from src.lib.torrent.protocol import TorrentProgress
from src.service.stream.reaper import TorrentReaper
from src.service.stream.session import StreamSession, StreamSessionStore


def _progress(info_hash: str) -> TorrentProgress:
    return TorrentProgress(
        info_hash=info_hash,
        state="live",
        finished=False,
        progress_bytes=0,
        uploaded_bytes=0,
        total_bytes=0,
        download_bps=0,
        upload_bps=0,
        peers_connected=0,
    )


class _FakeTorrentClient:
    def __init__(self, progress: list[TorrentProgress]) -> None:
        self._progress = progress
        self.deleted: list[str] = []

    async def list_progress(self) -> list[TorrentProgress]:
        return self._progress

    async def delete(self, info_hash: str) -> None:
        self.deleted.append(info_hash)


class _FakeTaskRepo:
    def __init__(self, owned_hashes: set[str]) -> None:
        self._owned_hashes = owned_hashes

    async def get_one(self, *, info_hash: str, deleted_at__isnull: bool) -> object | None:
        assert deleted_at__isnull is True
        return object() if info_hash in self._owned_hashes else None


@pytest.mark.asyncio
async def test_sweep_deletes_torrents_with_no_owning_task_and_no_live_session() -> None:
    client = _FakeTorrentClient([_progress("orphan-hash")])
    task_repo = _FakeTaskRepo(owned_hashes=set())
    sessions = StreamSessionStore()

    reaper = TorrentReaper(client=client, task_repo=task_repo, sessions=sessions)  # ty: ignore[invalid-argument-type]
    await reaper.sweep()

    assert client.deleted == ["orphan-hash"]


@pytest.mark.asyncio
async def test_sweep_keeps_torrents_owned_by_a_task() -> None:
    client = _FakeTorrentClient([_progress("owned-hash")])
    task_repo = _FakeTaskRepo(owned_hashes={"owned-hash"})
    sessions = StreamSessionStore()

    reaper = TorrentReaper(client=client, task_repo=task_repo, sessions=sessions)  # ty: ignore[invalid-argument-type]
    await reaper.sweep()

    assert client.deleted == []


@pytest.mark.asyncio
async def test_sweep_keeps_torrents_backing_a_live_stream_session() -> None:
    client = _FakeTorrentClient([_progress("live-hash")])
    task_repo = _FakeTaskRepo(owned_hashes=set())
    sessions = StreamSessionStore()
    sessions.add(
        StreamSession(
            id="s1",
            inputs=[MediaInput("http://example.com/a.mp4")],
            duration_seconds=12.0,
            has_video=True,
            segment_seconds=6,
            session_dir=Path("/tmp/s1"),
            encode_semaphore=asyncio.Semaphore(2),
            info_hash="live-hash",
        )
    )

    reaper = TorrentReaper(client=client, task_repo=task_repo, sessions=sessions)  # ty: ignore[invalid-argument-type]
    await reaper.sweep()

    assert client.deleted == []


@pytest.mark.asyncio
async def test_start_and_stop_the_background_loop() -> None:
    client = _FakeTorrentClient([])
    task_repo = _FakeTaskRepo(owned_hashes=set())
    sessions = StreamSessionStore()
    reaper = TorrentReaper(client=client, task_repo=task_repo, sessions=sessions, poll_s=0.01)  # ty: ignore[invalid-argument-type]

    await reaper.start()
    await reaper.stop()  # must return cleanly, not hang or raise
