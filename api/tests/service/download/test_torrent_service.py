import base64
import uuid
from collections import namedtuple
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code
from src.data.type import DownloadStatus, MediaKind, Platform
from src.lib.media.sidecar import TorrentFile
from src.lib.torrent import error as torrent_error
from src.lib.torrent.protocol import FileInfo, TorrentDetails
from src.service.download.disk import DiskGuard
from src.service.download.live import Live, LiveStats
from src.service.download.torrent_service import TorrentService

from tests.service.download.memory import (
    MemoryFiles,
    RecordingHub,
    download_row,
    memory_views,
    torrent_detail,
)

MAGNET = "magnet:?xt=urn:btih:abc123"
ROOT = Path("/workdir/download")

DETAILS = TorrentDetails(
    info_hash="abc123",
    name="Some Release",
    output_folder="/workdir/download/torrent/Some Release",
    files=[
        FileInfo(index=0, path="video.mkv", size_bytes=900),
        FileInfo(index=1, path="readme.txt", size_bytes=100),
    ],
)


class FakeTorrentClient:
    def __init__(self, *, details: TorrentDetails = DETAILS, fail: Error | None = None) -> None:
        self.details = details
        self.fail = fail
        self.resolved: list[Any] = []
        self.added: list[dict[str, Any]] = []
        self.paused: list[str] = []
        self.started: list[str] = []
        self.deleted: list[str] = []
        self.forgotten: list[str] = []

    async def resolve(self, source: Any) -> TorrentDetails:
        if self.fail:
            raise self.fail
        self.resolved.append(source)
        return self.details

    async def add(self, source: Any, *, only_files: Any, output_folder: str) -> TorrentDetails:
        if self.fail:
            raise self.fail
        self.added.append({"source": source, "only_files": list(only_files), "output_folder": output_folder})
        return self.details

    async def pause(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.paused.append(info_hash)

    async def start(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.started.append(info_hash)

    async def delete(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.deleted.append(info_hash)

    async def forget(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.forgotten.append(info_hash)


class FakeDownloadRepo:
    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, Any] = {}
        self.created: list[dict[str, Any]] = []
        self.files: MemoryFiles | None = None

    async def create_torrent(self, download: dict[str, Any], info_hash: str, files: list[Any]) -> Any:
        self.created.append({"download": download, "info_hash": info_hash, "files": list(files)})
        row = download_row(**download, torrent_detail=torrent_detail(info_hash))
        self.rows[row.id] = row
        if self.files is not None:
            await self.files.replace(row.id, files)
        return row

    async def get_active_by_id(self, download_id: uuid.UUID) -> Any:
        return self.rows.get(download_id)


def _service(
    client: FakeTorrentClient | None = None,
    *,
    repo: FakeDownloadRepo | None = None,
    files: MemoryFiles | None = None,
    hub: RecordingHub | None = None,
    live: LiveStats | None = None,
    root: Path = ROOT,
    enabled: bool = True,
    disk: DiskGuard | None = None,
) -> TorrentService:
    repo = repo or FakeDownloadRepo()
    files = files or MemoryFiles()
    repo.files = files
    live = live or LiveStats()
    return TorrentService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        file_repo=files,  # ty: ignore[invalid-argument-type]
        client=client or FakeTorrentClient(),  # ty: ignore[invalid-argument-type]
        hub=hub or RecordingHub(),  # ty: ignore[invalid-argument-type]
        views=memory_views(files=files, live=live),
        live=live,
        downloads_root=root,
        torrent_root=root / "torrent",
        enabled=enabled,
        disk=disk,
    )


_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _disk(free: int, min_free: int = 50) -> DiskGuard:
    """Sizes in bytes to match DETAILS: 900 for the video, 100 for the readme."""
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(10_000, 0, free))


def _torrent_row(repo: FakeDownloadRepo, **overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "source_url": MAGNET,
        "platform": Platform.TORRENT,
        "title": "Some Release",
        "status": DownloadStatus.DOWNLOADING,
        "downloaded_bytes": 400,
        "total_bytes": 1000,
        "folder": "Some Release",
        "torrent_detail": torrent_detail("abc123"),
    }
    fields.update(overrides)
    row = download_row(**fields)
    repo.rows[row.id] = row
    return row


# --- resolve and enqueue ---------------------------------------------------------------


@pytest.mark.asyncio
async def test_enqueue_refuses_a_selection_that_would_not_fit_before_the_engine_starts() -> None:
    client, repo = FakeTorrentClient(), FakeDownloadRepo()

    # The video alone is 900 bytes; with the 50-byte minimum it needs 950.
    with pytest.raises(Error) as caught:
        await _service(client, repo=repo, disk=_disk(free=949)).enqueue(MAGNET, [0])

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert client.added == []
    assert repo.created == []


@pytest.mark.asyncio
async def test_enqueue_counts_only_the_selected_files() -> None:
    client = FakeTorrentClient()

    # All 1000 bytes would not fit in 950, but the 900-byte video does.
    await _service(client, disk=_disk(free=950)).enqueue(MAGNET, [0])

    assert len(client.added) == 1


@pytest.mark.asyncio
async def test_resolve_returns_the_file_list_without_creating_a_download() -> None:
    repo = FakeDownloadRepo()
    response = await _service(repo=repo).resolve(MAGNET)

    assert (response.info_hash, response.title, response.total_bytes) == ("abc123", "Some Release", 1000)
    assert [f.index for f in response.files] == [0, 1]
    assert response.files[0].path == "video.mkv"
    assert all(f.selected for f in response.files)
    assert repo.created == []


@pytest.mark.asyncio
async def test_resolve_rejects_input_that_is_not_a_torrent() -> None:
    client = FakeTorrentClient()
    with pytest.raises(Error) as caught:
        await _service(client).resolve("not a torrent!!")

    assert caught.value.code == Code.BAD_REQUEST
    assert client.resolved == []


@pytest.mark.asyncio
async def test_resolve_is_unavailable_when_torrents_are_disabled() -> None:
    with pytest.raises(Error) as caught:
        await _service(enabled=False).resolve(MAGNET)
    assert caught.value.code == Code.SERVICE_UNAVAILABLE


@pytest.mark.asyncio
async def test_resolve_surfaces_an_engine_failure_unchanged() -> None:
    client = FakeTorrentClient(fail=torrent_error.metadata_timeout(30))
    with pytest.raises(Error) as caught:
        await _service(client).resolve(MAGNET)
    assert caught.value.code == Code.REQUEST_TIMEOUT


@pytest.mark.asyncio
async def test_enqueue_adds_to_the_engine_and_records_the_folder_relative_to_the_download_dir(
    tmp_path: Path,
) -> None:
    client, repo = FakeTorrentClient(), FakeDownloadRepo()

    schema = await _service(client, repo=repo, root=tmp_path).enqueue(MAGNET, [0])

    assert client.added[0]["only_files"] == [0]
    # Its own folder, and the row records the one rqbit is told (#107), relative to DOWNLOAD_DIR.
    assert client.added[0]["output_folder"] == str(tmp_path / "torrent" / "Some Release")
    created = repo.created[0]
    download = created["download"]
    assert download["folder"] == "torrent/Some Release"
    assert (download["platform"], download["media_kind"], download["status"]) == (
        Platform.TORRENT,
        MediaKind.FILE,
        DownloadStatus.PENDING,
    )
    assert (download["title"], download["source_url"]) == ("Some Release", MAGNET)
    # Only the selected file counts towards the size the UI shows.
    assert download["total_bytes"] == 900
    assert created["info_hash"] == "abc123"
    assert schema.type == "download"
    assert schema.torrent is not None and schema.torrent.info_hash == "abc123"


@pytest.mark.asyncio
async def test_enqueue_records_which_files_were_chosen() -> None:
    repo = FakeDownloadRepo()
    await _service(repo=repo).enqueue(MAGNET, [1])

    assert repo.created[0]["files"] == [(0, "video.mkv", 900, False), (1, "readme.txt", 100, True)]


@pytest.mark.asyncio
async def test_an_empty_selection_means_every_file() -> None:
    client, repo = FakeTorrentClient(), FakeDownloadRepo()

    await _service(client, repo=repo).enqueue(MAGNET, [])

    assert client.added[0]["only_files"] == []
    assert [selected for _, _, _, selected in repo.created[0]["files"]] == [True, True]


@pytest.mark.asyncio
async def test_a_selection_naming_no_real_file_is_rejected() -> None:
    client = FakeTorrentClient()
    with pytest.raises(Error) as caught:
        await _service(client).enqueue(MAGNET, [7])

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert client.added == []


@pytest.mark.asyncio
async def test_a_torrent_file_upload_stores_a_magnet_for_its_info_hash() -> None:
    """A base64 .torrent must not be written into source_url: reconciliation re-adds from it."""
    repo = FakeDownloadRepo()
    encoded = base64.b64encode(b"d8:announce1:xe").decode()

    await _service(repo=repo).enqueue(encoded, [0])

    assert repo.created[0]["download"]["source_url"] == "magnet:?xt=urn:btih:abc123"


@pytest.mark.asyncio
async def test_enqueue_publishes_the_new_download_with_its_files() -> None:
    hub = RecordingHub()

    await _service(hub=hub).enqueue(MAGNET, [0])

    (data,) = hub.named("download")
    assert data["torrent"]["info_hash"] == "abc123"
    assert [f["path"] for f in data["files"]] == ["video.mkv", "readme.txt"]


@pytest.mark.asyncio
async def test_enqueue_is_unavailable_when_torrents_are_disabled() -> None:
    with pytest.raises(Error) as caught:
        await _service(enabled=False).enqueue(MAGNET, [0])
    assert caught.value.code == Code.SERVICE_UNAVAILABLE


# --- control verbs ---------------------------------------------------------------------


@pytest.mark.asyncio
async def test_pause_pauses_the_engine_and_the_row_and_stills_its_numbers() -> None:
    repo, live, hub = FakeDownloadRepo(), LiveStats(), RecordingHub()
    row = _torrent_row(repo, status=DownloadStatus.DOWNLOADING)
    live.set(row.id, Live(speed_bps=100, peers=3))
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo, live=live, hub=hub).pause(row.id)

    assert client.paused == ["abc123"]
    assert schema.status == DownloadStatus.PAUSED
    assert live.get(row.id) == Live()
    assert hub.named("download")[0]["status"] == "paused"


@pytest.mark.asyncio
async def test_resume_starts_the_engine_and_clears_the_error() -> None:
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.FAILED, error="boom")
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo).resume(row.id)

    assert client.started == ["abc123"]
    assert schema.status == DownloadStatus.DOWNLOADING
    assert schema.error is None


@pytest.mark.asyncio
async def test_stop_seeding_pauses_the_engine_and_completes_the_row() -> None:
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    client = FakeTorrentClient()

    schema = await _service(client, repo=repo).stop_seeding(row.id)

    assert client.paused == ["abc123"]
    assert schema.status == DownloadStatus.COMPLETE


@pytest.mark.asyncio
async def test_stop_seeding_refuses_a_download_that_is_not_seeding() -> None:
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await _service(repo=repo).stop_seeding(row.id)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_cancel_deletes_from_the_engine_and_soft_deletes_the_row() -> None:
    repo, client = FakeDownloadRepo(), FakeTorrentClient()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)

    await _service(client, repo=repo).cancel(row.id)

    assert client.deleted == ["abc123"]
    assert row.status == DownloadStatus.CANCELED
    assert row.deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_keeping_files_forgets_rather_than_deletes() -> None:
    """`delete` takes the data with it; `forget` is the one that leaves it."""
    repo, client = FakeDownloadRepo(), FakeTorrentClient()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)

    await _service(client, repo=repo).cancel(row.id, delete_files=False)

    assert client.forgotten == ["abc123"]
    assert client.deleted == []
    assert row.status == DownloadStatus.CANCELED


@pytest.mark.asyncio
async def test_cancel_still_soft_deletes_when_the_engine_is_gone() -> None:
    """The user asked for it gone. An unreachable engine must not block that."""
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.DOWNLOADING)
    client = FakeTorrentClient(fail=torrent_error.engine_unavailable("refused"))

    await _service(client, repo=repo).cancel(row.id)

    assert row.status == DownloadStatus.CANCELED


# --- files -----------------------------------------------------------------------------


async def _with_files(files: MemoryFiles, row: Any, rows: list[tuple[int, str, int, bool]]) -> None:
    await files.replace(row.id, rows)


@pytest.mark.asyncio
async def test_resolve_file_returns_the_path_for_an_index(tmp_path: Path) -> None:
    folder = tmp_path / "Some Release"
    folder.mkdir()
    (folder / "video.mkv").write_bytes(b"data")
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    await _with_files(files, row, [(0, "video.mkv", 4, True), (1, "readme.txt", 1, False)])

    path, filename, media_type = await _service(repo=repo, files=files, root=tmp_path).resolve_file(row.id, 0)

    assert path == (folder / "video.mkv").resolve()
    assert filename == "video.mkv"
    assert media_type == "video/x-matroska"


@pytest.mark.asyncio
async def test_resolve_file_refuses_a_file_that_was_not_selected(tmp_path: Path) -> None:
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    await _with_files(files, row, [(1, "readme.txt", 1, False)])

    with pytest.raises(Error) as caught:
        await _service(repo=repo, files=files, root=tmp_path).resolve_file(row.id, 1)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_resolve_file_404s_on_an_unknown_index(tmp_path: Path) -> None:
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)

    with pytest.raises(Error) as caught:
        await _service(repo=repo, root=tmp_path).resolve_file(row.id, 9)
    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_resolve_file_409s_while_the_torrent_is_still_downloading(tmp_path: Path) -> None:
    repo = FakeDownloadRepo()
    row = _torrent_row(repo, status=DownloadStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await _service(repo=repo, root=tmp_path).resolve_file(row.id, 0)
    assert caught.value.code == Code.CONFLICT


@pytest.mark.asyncio
async def test_resolve_file_refuses_a_path_escaping_the_output_folder(tmp_path: Path) -> None:
    """A torrent's file names come from a stranger. They do not get to escape."""
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    await _with_files(files, row, [(0, "../../etc/passwd", 1, True)])

    with pytest.raises(Error) as caught:
        await _service(repo=repo, files=files, root=tmp_path).resolve_file(row.id, 0)
    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_the_file_to_play_is_the_largest_selected_media_file() -> None:
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    await _with_files(
        files,
        row,
        [
            (0, "Sample.mkv", 50, True),
            (1, "Movie.mkv", 900, True),
            (2, "Extras.mkv", 5000, False),
            (3, "Movie.iso", 9000, True),
            (4, "Movie.en.srt", 10, True),
        ],
    )

    assert await _service(repo=repo, files=files).media_file_index(row.id) == 1


@pytest.mark.asyncio
async def test_a_torrent_with_no_selected_media_has_nothing_to_play() -> None:
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.SEEDING)
    await _with_files(files, row, [(0, "readme.txt", 1, True), (1, "Movie.mkv", 1, False)])

    with pytest.raises(Error) as caught:
        await _service(repo=repo, files=files).media_file_index(row.id)
    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


@pytest.mark.asyncio
async def test_a_named_file_to_play_must_be_a_selected_media_file() -> None:
    """What a torrent still downloading may play (#95): what it's downloading."""
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=DownloadStatus.DOWNLOADING)
    await _with_files(files, row, [(0, "E1.mkv", 1, True), (1, "E2.mkv", 1, False), (2, "E1.en.srt", 1, True)])
    service = _service(repo=repo, files=files)

    assert await service.media_file_index(row.id, 0) == 0
    for refused in (1, 2, 9):
        with pytest.raises(Error) as caught:
            await service.media_file_index(row.id, refused)
        assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- subtitle files beside the video (#101) --------------------------------------------


async def _release(tmp_path: Path, status: DownloadStatus) -> tuple[TorrentService, uuid.UUID, Path]:
    """A film with a subtitle file beside it on disk, and one in Subs/ that wasn't selected."""
    folder = tmp_path / "Some Release"
    (folder / "Subs").mkdir(parents=True)
    (folder / "Movie.mkv").write_bytes(b"x" * 900)
    (folder / "Movie.en.srt").write_bytes(b"s" * 10)
    repo, files = FakeDownloadRepo(), MemoryFiles()
    row = _torrent_row(repo, status=status)
    await _with_files(
        files,
        row,
        [
            (0, "Movie.mkv", 900, True),
            (1, "Movie.en.srt", 10, True),
            (2, "Subs/2_French.srt", 12, False),
            (3, "Other.en.srt", 10, True),
        ],
    )
    return _service(repo=repo, files=files, root=tmp_path), row.id, folder


@pytest.mark.asyncio
async def test_a_downloading_torrent_offers_its_subtitle_files_from_disk_or_rqbit(tmp_path: Path) -> None:
    service, download_id, folder = await _release(tmp_path, DownloadStatus.DOWNLOADING)

    found = await service.subtitle_files(download_id, None)

    assert [(sidecar.path, source) for sidecar, source in found] == [
        ("Movie.en.srt", (folder / "Movie.en.srt").resolve()),
        # Not selected, and not on disk: rqbit streams it all the same (#93).
        ("Subs/2_French.srt", TorrentFile("abc123", 2)),
    ]
    assert [sidecar.language for sidecar, _ in found] == ["en", "fr"]


@pytest.mark.asyncio
async def test_a_half_written_subtitle_file_is_read_through_rqbit(tmp_path: Path) -> None:
    service, download_id, folder = await _release(tmp_path, DownloadStatus.SEEDING)
    (folder / "Movie.en.srt").write_bytes(b"s" * 4)

    found = await service.subtitle_files(download_id, 0)

    assert found[0][1] == TorrentFile("abc123", 1)


@pytest.mark.asyncio
async def test_a_finished_torrent_rqbit_no_longer_has_offers_only_what_s_on_disk(tmp_path: Path) -> None:
    service, download_id, folder = await _release(tmp_path, DownloadStatus.COMPLETE)

    found = await service.subtitle_files(download_id, None)

    assert [(sidecar.path, source) for sidecar, source in found] == [
        ("Movie.en.srt", (folder / "Movie.en.srt").resolve())
    ]
