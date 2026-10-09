import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from src.data.type import DownloadStatus, MediaKind, Preset, SourceKind
from src.service.download.live import Live, LiveStats
from src.service.download.views import DownloadViews, download_schema, progress_frame

from tests.service.download.described_rows import a_mirror, a_torrent


def row(**overrides: Any) -> SimpleNamespace:
    base: dict[str, Any] = dict(
        id=uuid.uuid4(),
        mirrors=[a_mirror("https://youtu.be/dQw4w9WgXcQ", SourceKind.CONTENT, "Youtube")],
        uploaded_size=0,
        status=DownloadStatus.DOWNLOADING,
        folder=None,
        parent_id=None,
        speed_limit=None,
        total_size=200,
        downloaded_size=50,
        media=SimpleNamespace(
            title="Talk", kind=MediaKind.VIDEO, preset=Preset.BEST, video_format="137", audio_format="140"
        ),
        error=None,
        error_code=None,
        attempts=1,
        next_attempt_at=None,
        created_at=None,
        started_at=None,
        completed_at=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def file(index: int = 0, path: str = "Talk.mp4") -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid.uuid4(),
        index=index,
        path=path,
        size=0,
        downloaded_bytes=0,
        selected=True,
        mime_type="video/mp4",
    )


def test_schema_computes_progress_and_carries_each_block() -> None:
    one = file()
    schema = download_schema(
        row(),
        files=[one],
        playback={one.id: SimpleNamespace(position_seconds=4.0, duration_seconds=9.0, watched=False)},
        live=Live(speed_bps=7, eta_seconds=3),
        max_attempts=3,
    )
    assert schema.progress == 25
    assert schema.site is not None and (schema.site.video_format, schema.site.video_id) == ("137", "dQw4w9WgXcQ")
    assert (schema.title, schema.url) == ("Talk", "https://youtu.be/dQw4w9WgXcQ")
    assert schema.torrent is None
    assert schema.live.speed_bps == 7
    assert schema.files[0].playback is not None and schema.files[0].playback.position_seconds == 4.0
    assert schema.max_attempts == 3


def test_a_torrent_reports_its_hash_and_no_site() -> None:
    schema = download_schema(
        row(
            mirrors=[
                a_mirror("magnet:?xt=urn:btih:" + "a" * 40, SourceKind.TORRENT, "torrent", torrents=[a_torrent("a" * 40)])
            ],
            media=None,
            uploaded_size=9,
        ),
        files=[],
        playback={},
        live=Live(),
        max_attempts=3,
    )
    assert schema.torrent is not None and (schema.torrent.info_hash, schema.torrent.uploaded_bytes) == ("a" * 40, 9)
    assert schema.site is None


def test_progress_frame_leaves_out_what_does_not_apply() -> None:
    download_id, collection_id = uuid.uuid4(), uuid.uuid4()
    frame = progress_frame(download_id, collection_id=None, downloaded_bytes=5, total_bytes=None, live=Live(speed_bps=1))
    assert frame == {
        "id": str(download_id),
        "downloaded_size": 5,
        "progress": 0,
        "live": {"speed_bps": 1, "eta_seconds": None, "upload_speed_bps": 0, "peers": 0},
    }
    member = progress_frame(
        download_id, collection_id=collection_id, downloaded_bytes=5, total_bytes=10, live=Live(), files=[(0, 5)]
    )
    assert member["collection_id"] == str(collection_id)
    assert member["progress"] == 50
    assert member["files"] == [{"index": 0, "downloaded_bytes": 5}]


class Repos:
    def __init__(self, files: dict[uuid.UUID, list[Any]]) -> None:
        self._files = files

    async def list_for_downloads(self, ids: list[uuid.UUID]) -> dict[uuid.UUID, list[Any]]:
        return {i: self._files.get(i, []) for i in ids}

    async def by_files(self, ids: list[uuid.UUID]) -> dict[uuid.UUID, Any]:
        return {}


@pytest.mark.asyncio
async def test_many_batches_and_reads_live_numbers() -> None:
    first, second = row(), row()
    live = LiveStats()
    live.set(second.id, Live(speed_bps=99))
    repos = Repos({first.id: [file()]})
    views = DownloadViews(files=repos, positions=repos, live=live, max_attempts=3)  # ty: ignore[invalid-argument-type]
    schemas = await views.many([first, second])
    assert [len(s.files) for s in schemas] == [1, 0]
    assert schemas[1].live.speed_bps == 99


def test_a_download_shows_its_category_and_its_stored_folder() -> None:
    lectures = SimpleNamespace(id=uuid.uuid4(), name="Lectures", folder="edu")
    stored = row(status=DownloadStatus.COMPLETED, category=lectures, folder="edu")
    schema = download_schema(stored, files=[], playback={}, live=Live(), max_attempts=3)
    assert schema.category is not None and (schema.category.id, schema.category.name) == (lectures.id, "Lectures")
    assert schema.folder == "edu"


def test_a_stored_folder_shows_before_the_file_lands() -> None:
    placed = row(status=DownloadStatus.DOWNLOADING, folder="Release [deadbeef]")
    assert download_schema(placed, files=[], playback={}, live=Live(), max_attempts=3).folder == "Release [deadbeef]"


def test_a_row_loaded_without_its_category_has_none() -> None:
    bare = row(status=DownloadStatus.DOWNLOADING)
    assert download_schema(bare, files=[], playback={}, live=Live(), max_attempts=3).category is None
