import uuid
from collections import namedtuple
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.success import Meta
from src.core.type import Code
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.site import error as site_error
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard
from src.service.download.download_service import DownloadService, TorrentPlay

from tests.sites import FakeSiteClient, site_info, sized

YOUTUBE = "https://youtu.be/dQw4w9WgXcQ"


class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.rows: dict[uuid.UUID, Any] = {}
        self.listed_statuses: list[Any] | None = None
        self.listed_sort: str | None = None
        #: What ``list_page`` answers with.
        self.page: list[Any] = []

    async def list_page(
        self,
        page: int,
        page_size: int,
        statuses: list[Any] | None = None,
        sort: str = "-created_at",
    ) -> tuple[list[Any], Meta]:
        self.listed_statuses = statuses
        self.listed_sort = sort
        return list(self.page), Meta(page=page, page_size=page_size, total=len(self.page), total_pages=1)

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()

    async def get_active_by_id(self, task_id: uuid.UUID) -> Any:
        return self.rows.get(task_id)

    async def by_statuses(self, statuses: list[Any]) -> list[Any]:
        wanted = set(statuses)
        return [row for row in self.rows.values() if row.status in wanted]


def _row(task_id: uuid.UUID, **overrides: Any) -> Any:
    """A stand-in for a Task row, with a no-op ``save``."""
    fields: dict[str, Any] = {
        "id": task_id,
        "source_url": "https://youtu.be/x",
        "platform": Platform.SITE,
        "extractor": "Youtube",
        "video_id": "x",
        "preset": Preset.BEST,
        "kind": Kind.VIDEO,
        "title": "clip",
        "filename": "clip.mp4",
        "mime_type": "video/mp4",
        "status": TaskStatus.PENDING,
        "progress": 0,
        "downloaded_bytes": 0,
        "total_bytes": None,
        "speed_bps": 0,
        "eta_seconds": None,
        "file_path": None,
        "file_size": None,
        "error": None,
        "error_code": None,
        "attempts": 0,
        "next_attempt_at": None,
        "deleted_at": None,
        "created_at": None,
        "started_at": None,
        "completed_at": None,
    }
    fields.update(overrides)
    row = type("Row", (), fields)()

    async def _save(*_args: Any, **_kwargs: Any) -> None:
        return None

    row.save = _save
    return row


class FakeSegmentRepo:
    """Cancel clears a task's segment rows; nothing else here touches them."""

    def __init__(self) -> None:
        self.cleared: list[uuid.UUID] = []

    async def clear(self, task_id: uuid.UUID, part: str | None = None) -> None:
        self.cleared.append(task_id)


class FakeTorrentService:
    """Stands in for ``TorrentService`` in the branches ``DownloadService`` delegates to."""

    def __init__(self) -> None:
        self.paused: list[uuid.UUID] = []
        self.resumed: list[uuid.UUID] = []
        self.canceled: list[uuid.UUID] = []
        self.canceled_with_files: list[bool] = []
        self.schema_batches: list[list[Any]] = []
        self.only_files: list[uuid.UUID] = []

    async def schema(self, task: Any) -> Any:
        return (await self.schemas([task]))[0]

    async def schemas(self, tasks: Any) -> list[Any]:
        """Tags each row so a test can tell it went through here."""
        self.schema_batches.append(list(tasks))
        return [("via-torrents", task.id) for task in tasks]

    async def resolve_only_file(self, task_id: uuid.UUID) -> tuple[Path, str, str]:
        self.only_files.append(task_id)
        return Path("/t/video.mkv"), "video.mkv", "video/x-matroska"

    async def resolve_file(self, task_id: uuid.UUID, index: int) -> tuple[Path, str, str]:
        return Path(f"/t/file{index}.mkv"), f"file{index}.mkv", "video/x-matroska"

    async def media_file_index(self, task_id: uuid.UUID, wanted: int | None = None) -> int:
        return 7 if wanted is None else wanted

    async def pause(self, task_id: uuid.UUID) -> Any:
        self.paused.append(task_id)
        return type("Row", (), {"status": TaskStatus.PAUSED})()

    async def resume(self, task_id: uuid.UUID) -> Any:
        self.resumed.append(task_id)
        return type("Row", (), {"status": TaskStatus.DOWNLOADING})()

    async def cancel(self, task_id: uuid.UUID, *, delete_files: bool = True) -> None:
        self.canceled.append(task_id)
        self.canceled_with_files.append(delete_files)


def _service(
    downloads_dir: Path | None = None,
    disk: DiskGuard | None = None,
    client: FakeSiteClient | None = None,
) -> tuple[DownloadService, FakeRepo, FakeTorrentService]:
    repo = FakeRepo()
    torrents = FakeTorrentService()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        segment_repo=FakeSegmentRepo(),  # ty: ignore[invalid-argument-type]
        client=client or FakeSiteClient(site_info("youtube")),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=downloads_dir or Path("/tmp/anydm-test"),
        torrents=torrents,  # ty: ignore[invalid-argument-type]
        disk=disk,
    )
    return service, repo, torrents


GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _disk(free: int, min_free: int = GIB) -> DiskGuard:
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(100 * GIB, 0, free))


# --- enqueue from a site ------------------------------------------------------


@pytest.mark.asyncio
async def test_enqueue_media_writes_a_pending_site_row() -> None:
    service, repo, _ = _service()
    task = await service.enqueue_media(YOUTUBE, Preset.P1080)

    assert task.extractor == "Youtube"  # the site reaches the UI too
    row = repo.created[0]
    assert row["status"] == TaskStatus.PENDING
    assert (row["platform"], row["extractor"], row["video_id"]) == (Platform.SITE, "Youtube", "dQw4w9WgXcQ")
    assert (row["preset"], row["kind"]) == (Preset.P1080, Kind.VIDEO)
    assert row["source_url"] == YOUTUBE
    assert row["progress"] == 0


@pytest.mark.asyncio
async def test_enqueue_media_stores_the_plan_s_format_ids() -> None:
    service, repo, _ = _service()
    await service.enqueue_media(YOUTUBE, Preset.P1080)

    row = repo.created[0]
    assert (row["video_format"], row["audio_format"]) == ("137", "140")
    assert row["title"] == site_info("youtube").title
    assert row["filename"].endswith("_1080p.mp4")
    assert row["total_bytes"] == 80_911_999 + 3_449_447


@pytest.mark.asyncio
async def test_a_combined_format_is_one_part() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("vimeo")))
    await service.enqueue_media("http://vimeo.com/75629013", Preset.BEST)

    row = repo.created[0]
    assert (row["video_format"], row["audio_format"], row["extractor"]) == ("http-1080p", None, "Vimeo")
    assert row["total_bytes"] is None


@pytest.mark.asyncio
async def test_an_estimated_size_is_not_stored_as_the_total() -> None:
    # A wrong total would stall or overshoot the progress bar; the probe will
    # learn the real one. The estimate still counts for the disk guard.
    service, repo, _ = _service(client=FakeSiteClient(site_info("twitter")))
    await service.enqueue_media("https://twitter.com/x/status/1", Preset.BEST)

    assert repo.created[0]["total_bytes"] is None


@pytest.mark.asyncio
async def test_an_mp3_from_an_audio_site() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("soundcloud")))
    await service.enqueue_media("http://soundcloud.com/x/y", Preset.MP3)

    row = repo.created[0]
    assert (row["kind"], row["video_format"], row["audio_format"]) == (Kind.AUDIO, None, "http_mp3_0_0")
    assert row["filename"].endswith(".mp3")


@pytest.mark.asyncio
async def test_a_taller_preset_than_available_falls_back_to_the_tallest() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("twitter")))
    await service.enqueue_media("https://twitter.com/x/status/1", Preset.P2160)

    assert repo.created[0]["video_format"] == "http-2176"


@pytest.mark.asyncio
async def test_a_site_with_only_streaming_formats_is_queued_for_the_fragment_path() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("dailymotion")))

    await service.enqueue_media("https://dailymotion.com/video/x", Preset.BEST)

    created = repo.created[0]
    assert created["video_format"] == "hls-1080"
    assert created["filename"].endswith(".mp4")


@pytest.mark.asyncio
async def test_the_tallest_format_wins_even_when_it_is_streaming_only() -> None:
    # Reddit's tallest is HLS-only at 640p; its HTTPS formats stop at 480p.
    service, repo, _ = _service(client=FakeSiteClient(site_info("reddit")))
    await service.enqueue_media("https://reddit.com/r/x", Preset.BEST)

    assert (repo.created[0]["video_format"], repo.created[0]["audio_format"]) == ("hls-1875", "dash-AUDIO-1")


@pytest.mark.asyncio
async def test_a_live_stream_is_refused() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("twitch", is_live=True)))

    with pytest.raises(Error) as caught:
        await service.enqueue_media("https://twitch.tv/x", Preset.BEST)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert repo.created == []


@pytest.mark.asyncio
async def test_a_preset_the_site_cannot_satisfy_is_refused() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("soundcloud")))

    with pytest.raises(Error) as caught:
        await service.enqueue_media("http://soundcloud.com/x/y", Preset.P1080)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert repo.created == []


@pytest.mark.asyncio
async def test_an_extraction_failure_reaches_the_caller() -> None:
    client = FakeSiteClient(site_info("youtube"), fail=site_error.unsupported_url("https://example.test/x"))
    service, repo, _ = _service(client=client)

    with pytest.raises(Error) as caught:
        await service.enqueue_media("https://example.test/x", Preset.BEST)

    assert caught.value.code == Code.BAD_REQUEST
    assert repo.created == []


@pytest.mark.asyncio
async def test_resolve_file_rejects_an_incomplete_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING, file_path=None)

    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_resolve_file_returns_the_path_for_a_complete_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    target = tmp_path / str(task_id) / "clip.mp4"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"x")
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/clip.mp4", filename="clip.mp4"
    )

    path, filename, media_type = await service.resolve_file(task_id)
    assert path == target
    assert filename == "clip.mp4"
    assert media_type == "video/mp4"


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_row_is_gone(tmp_path: Path) -> None:
    service, _, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.resolve_file(uuid.uuid4())
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_file_vanished(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/gone.mp4", filename="gone.mp4"
    )
    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code == 404


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [TaskStatus.SEEDING, TaskStatus.COMPLETE])
async def test_resolve_file_hands_a_torrent_to_the_torrent_service(status: TaskStatus) -> None:
    """#107: a torrent's file_path is a folder, so this answered 404, or 409 while seeding."""
    service, repo, torrents = _service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, platform=Platform.TORRENT, kind=Kind.TORRENT, status=status, file_path="/t"
    )

    path, filename, _ = await service.resolve_file(task_id)

    assert torrents.only_files == [task_id]
    assert (path, filename) == (Path("/t/video.mkv"), "video.mkv")


@pytest.mark.asyncio
async def test_the_list_goes_through_the_torrent_service_as_one_batch() -> None:
    service, repo, torrents = _service()
    rows = [_row(uuid.uuid4()), _row(uuid.uuid4(), platform=Platform.TORRENT, kind=Kind.TORRENT)]
    repo.page = rows

    tasks, _meta = await service.list_tasks(page=1, page_size=20)

    assert torrents.schema_batches == [rows]
    assert tasks == [("via-torrents", row.id) for row in rows]


@pytest.mark.asyncio
async def test_one_task_goes_through_the_torrent_service() -> None:
    service, repo, _ = _service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, kind=Kind.TORRENT)

    assert await service.get_task(task_id) == ("via-torrents", task_id)


@pytest.mark.asyncio
async def test_pause_stops_a_running_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    result = await service.pause(task_id)

    assert result.status == TaskStatus.PAUSED
    assert service._control.is_stopping(task_id) is True


@pytest.mark.asyncio
async def test_pause_also_works_on_a_queued_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PENDING)
    assert (await service.pause(task_id)).status == TaskStatus.PAUSED


@pytest.mark.asyncio
async def test_pause_rejects_a_completed_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    with pytest.raises(Error) as caught:
        await service.pause(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_resume_requeues_a_paused_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PAUSED)
    service._control.request_stop(task_id)

    result = await service.resume(task_id)

    assert result.status == TaskStatus.PENDING
    assert service._control.is_stopping(task_id) is False


@pytest.mark.asyncio
async def test_resume_clears_the_failure_state(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.FAILED, error="boom", error_code="dependency_failure", attempts=3
    )

    result = await service.resume(task_id)

    assert result.status == TaskStatus.PENDING
    assert result.error is None
    assert result.error_code is None
    assert result.attempts == 0


@pytest.mark.asyncio
async def test_resume_rejects_a_running_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await service.resume(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_listing_a_group_asks_for_the_statuses_it_means(tmp_path: Path) -> None:
    """The sidebar's "Active" includes queued rows; the orphan check does not."""
    service, repo, _ = _service(downloads_dir=tmp_path)

    await service.list_tasks(page=1, page_size=10, group="downloading")

    assert repo.listed_statuses is not None
    assert set(repo.listed_statuses) == {
        TaskStatus.PENDING,
        TaskStatus.DOWNLOADING,
        TaskStatus.MUXING,
    }


@pytest.mark.asyncio
async def test_listing_everything_asks_for_no_statuses_at_all(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)

    await service.list_tasks(page=1, page_size=10, group="all")

    assert repo.listed_statuses is None


@pytest.mark.asyncio
async def test_a_sort_reaches_the_repository_as_asked(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)

    await service.list_tasks(page=1, page_size=10, sort="-total_bytes")

    assert repo.listed_sort == "-total_bytes"


@pytest.mark.asyncio
async def test_listing_defaults_to_newest_first(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)

    await service.list_tasks(page=1, page_size=10)

    assert repo.listed_sort == "-created_at"


@pytest.mark.asyncio
async def test_a_sort_on_a_column_not_offered_is_refused(tmp_path: Path) -> None:
    """The route types this too; this guards against a caller inside the process
    reaching the database's ORDER BY with something arbitrary."""
    service, _, _ = _service(downloads_dir=tmp_path)

    for attempt in ("db_password", "-nonsense", ""):
        with pytest.raises(Error) as caught:
            await service.list_tasks(page=1, page_size=10, sort=attempt)
        assert caught.value.code == 400


@pytest.mark.asyncio
async def test_listing_an_unknown_group_is_rejected(tmp_path: Path) -> None:
    service, _, _ = _service(downloads_dir=tmp_path)

    with pytest.raises(Error) as caught:
        await service.list_tasks(page=1, page_size=10, group="nonsense")

    # The route also types this parameter, so a request never gets this far;
    # this guards the service against a caller inside the process.
    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_cancel_stops_the_task_and_removes_its_files(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    (tmp_path / str(task_id)).mkdir(parents=True)
    (tmp_path / str(task_id) / "video.part").write_bytes(b"x")
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    await service.cancel(task_id)

    assert not (tmp_path / str(task_id)).exists()
    assert repo.rows[task_id].status == TaskStatus.CANCELED
    assert repo.rows[task_id].deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_can_keep_the_files_of_a_finished_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    (tmp_path / str(task_id)).mkdir(parents=True)
    (tmp_path / str(task_id) / "video.mp4").write_bytes(b"x")
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    await service.cancel(task_id, delete_files=False)

    assert (tmp_path / str(task_id) / "video.mp4").read_bytes() == b"x"
    assert repo.rows[task_id].status == TaskStatus.CANCELED
    assert repo.rows[task_id].deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_refuses_to_keep_the_files_of_an_unfinished_task(
    tmp_path: Path,
) -> None:
    """A `.part` is meaningless once its row and byte watermarks are gone."""
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await service.cancel(task_id, delete_files=False)

    assert caught.value.code == 409
    assert repo.rows[task_id].status == TaskStatus.DOWNLOADING


@pytest.mark.asyncio
async def test_cancel_404s_on_an_unknown_task(tmp_path: Path) -> None:
    service, _, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.cancel(uuid.uuid4())
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_pause_delegates_a_torrent_to_the_torrent_service(tmp_path: Path) -> None:
    service, repo, torrents = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, status=TaskStatus.DOWNLOADING)

    result = await service.pause(task_id)

    assert torrents.paused == [task_id]
    assert result.status == TaskStatus.PAUSED


@pytest.mark.asyncio
async def test_resume_delegates_a_torrent_to_the_torrent_service(tmp_path: Path) -> None:
    service, repo, torrents = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, status=TaskStatus.PAUSED)

    result = await service.resume(task_id)

    assert torrents.resumed == [task_id]
    assert result.status == TaskStatus.DOWNLOADING


@pytest.mark.asyncio
async def test_cancel_delegates_a_torrent_to_the_torrent_service(tmp_path: Path) -> None:
    """A torrent's files are the engine's to remove, not this service's."""
    service, repo, torrents = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, status=TaskStatus.SEEDING)

    await service.cancel(task_id)

    assert torrents.canceled == [task_id]
    assert torrents.canceled_with_files == [True]


@pytest.mark.asyncio
async def test_keeping_a_torrents_files_is_the_engine_s_decision_to_make(
    tmp_path: Path,
) -> None:
    """This service must not try to keep a torrent's files itself."""
    service, repo, torrents = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, status=TaskStatus.SEEDING)

    await service.cancel(task_id, delete_files=False)

    assert torrents.canceled_with_files == [False]


@pytest.mark.asyncio
async def test_enqueue_url_writes_a_direct_task(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    await service.enqueue_url("https://cdn.test/files/report.pdf")

    row = repo.created[0]
    assert row["platform"] == Platform.DIRECT
    assert row["kind"] == Kind.FILE
    assert row["filename"] == "report.pdf"
    assert row["video_format"] is None
    assert row["audio_format"] is None
    assert row["video_id"] is None
    assert row["status"] == TaskStatus.PENDING


@pytest.mark.asyncio
async def test_enqueue_url_rejects_a_non_http_scheme(tmp_path: Path) -> None:
    service, _, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.enqueue_url("file:///etc/passwd")
    assert caught.value.code == 400


# --- disk space -------------------------------------------------------------


@pytest.mark.asyncio
async def test_enqueue_url_refuses_when_free_space_is_below_the_minimum() -> None:
    service, repo, _ = _service(disk=_disk(free=GIB // 2))

    with pytest.raises(Error) as caught:
        await service.enqueue_url("https://cdn.test/files/report.pdf")

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert repo.created == []


@pytest.mark.asyncio
async def test_enqueue_url_needs_only_the_minimum_since_its_size_is_not_known_yet() -> None:
    service, repo, _ = _service(disk=_disk(free=GIB + 1))

    await service.enqueue_url("https://cdn.test/files/huge.iso")

    assert len(repo.created) == 1


@pytest.mark.asyncio
async def test_enqueue_media_refuses_a_plan_that_would_not_fit() -> None:
    # 4 GiB of streams plus the 1 GiB minimum is 5 GiB; 4.5 GiB is free.
    info = sized(site_info("youtube"), {"137": 3 * GIB, "140": GIB})
    service, repo, _ = _service(disk=_disk(free=9 * GIB // 2), client=FakeSiteClient(info))

    with pytest.raises(Error) as caught:
        await service.enqueue_media(YOUTUBE, Preset.P1080)

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert repo.created == []


@pytest.mark.asyncio
async def test_enqueue_media_accepts_a_plan_that_fits() -> None:
    info = sized(site_info("youtube"), {"137": 3 * GIB, "140": GIB})
    service, repo, _ = _service(disk=_disk(free=5 * GIB), client=FakeSiteClient(info))

    await service.enqueue_media(YOUTUBE, Preset.P1080)

    assert repo.created[0]["total_bytes"] == 4 * GIB


@pytest.mark.asyncio
async def test_an_estimate_still_counts_for_the_disk_guard() -> None:
    # X's 720p is estimated at 862,240 bytes; with a 1 GiB minimum, 1 GiB plus
    # a few bytes is not enough.
    service, _, _ = _service(disk=_disk(free=GIB + 1000), client=FakeSiteClient(site_info("twitter")))

    with pytest.raises(Error) as caught:
        await service.enqueue_media("https://twitter.com/x/status/1", Preset.BEST)

    assert caught.value.code == Code.INSUFFICIENT_STORAGE


@pytest.mark.asyncio
async def test_an_unknown_size_needs_only_the_minimum() -> None:
    service, repo, _ = _service(disk=_disk(free=GIB + 1), client=FakeSiteClient(site_info("vimeo")))

    await service.enqueue_media("http://vimeo.com/75629013", Preset.BEST)

    assert len(repo.created) == 1


# --- bulk actions -----------------------------------------------------------


def _bulk_service(tmp_path: Path) -> tuple[DownloadService, FakeRepo, Any]:
    service, repo, torrents = _service(downloads_dir=tmp_path)
    for status in (
        TaskStatus.PENDING,
        TaskStatus.DOWNLOADING,
        TaskStatus.PAUSED,
        TaskStatus.SEEDING,
        TaskStatus.COMPLETE,
        TaskStatus.FAILED,
    ):
        task_id = uuid.uuid4()
        platform = Platform.TORRENT if status == TaskStatus.SEEDING else Platform.DIRECT
        repo.rows[task_id] = _row(task_id, status=status, platform=platform)
    return service, repo, torrents


def _statuses(repo: FakeRepo) -> list[TaskStatus]:
    return sorted(row.status for row in repo.rows.values())


@pytest.mark.asyncio
async def test_pause_all_takes_only_what_can_be_paused(tmp_path: Path) -> None:
    service, repo, _ = _bulk_service(tmp_path)

    affected = await service.bulk("pause_all")

    # pending, downloading and the seeding torrent; not paused, complete or failed.
    assert affected == 3
    assert _statuses(repo).count(TaskStatus.PAUSED) == 3
    assert TaskStatus.COMPLETE in _statuses(repo)
    assert TaskStatus.FAILED in _statuses(repo)


@pytest.mark.asyncio
async def test_resume_all_takes_only_what_can_be_resumed(tmp_path: Path) -> None:
    service, repo, _ = _bulk_service(tmp_path)

    affected = await service.bulk("resume_all")

    # The paused one and the failed one, both back to pending.
    assert affected == 2
    assert _statuses(repo).count(TaskStatus.PENDING) == 3


@pytest.mark.asyncio
async def test_clear_finished_keeps_a_finished_file_but_not_a_failed_one(
    tmp_path: Path,
) -> None:
    """Nothing worth keeping survives a failure, and keeping it would 409."""
    service, repo, _ = _bulk_service(tmp_path)
    finished = next(
        row for row in repo.rows.values() if row.status == TaskStatus.COMPLETE
    )
    failed = next(row for row in repo.rows.values() if row.status == TaskStatus.FAILED)
    for row in (finished, failed):
        (tmp_path / str(row.id)).mkdir(parents=True)
        (tmp_path / str(row.id) / "f.bin").write_bytes(b"x")

    affected = await service.bulk("clear_finished")

    assert affected == 2
    assert (tmp_path / str(finished.id) / "f.bin").exists()
    assert not (tmp_path / str(failed.id)).exists()


@pytest.mark.asyncio
async def test_clear_finished_can_take_the_files_too(tmp_path: Path) -> None:
    service, repo, _ = _bulk_service(tmp_path)
    finished = next(
        row for row in repo.rows.values() if row.status == TaskStatus.COMPLETE
    )
    (tmp_path / str(finished.id)).mkdir(parents=True)
    (tmp_path / str(finished.id) / "f.bin").write_bytes(b"x")

    await service.bulk("clear_finished", delete_files=True)

    assert not (tmp_path / str(finished.id)).exists()


@pytest.mark.asyncio
async def test_one_row_refusing_does_not_end_the_sweep(tmp_path: Path) -> None:
    service, repo, _ = _bulk_service(tmp_path)
    doomed = next(
        row for row in repo.rows.values() if row.status == TaskStatus.DOWNLOADING
    )

    async def _explode(*_args: Any, **_kwargs: Any) -> None:
        raise Error.conflict(message="no")

    doomed.save = _explode

    affected = await service.bulk("pause_all")

    # The other two still paused; the count reports what actually happened.
    assert affected == 2


@pytest.mark.asyncio
async def test_an_unknown_bulk_action_is_refused(tmp_path: Path) -> None:
    service, _, _ = _bulk_service(tmp_path)

    with pytest.raises(Error) as caught:
        await service.bulk("delete_everything")

    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_the_media_file_of_a_download_is_its_file(tmp_path: Path) -> None:
    """What Play on a finished card reads from disk (#94)."""
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    (tmp_path / "clip.mp4").write_bytes(b"x")
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE, file_path="clip.mp4")

    assert await service.resolve_media_file(task_id, None) == (tmp_path / "clip.mp4", "clip.mp4", None)


@pytest.mark.asyncio
async def test_a_download_has_no_file_at_an_index(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE, file_path="clip.mp4")

    with pytest.raises(Error) as caught:
        await service.resolve_media_file(task_id, 2)
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_the_media_file_of_a_torrent_is_the_one_asked_for_or_its_largest() -> None:
    service, repo, _ = _service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, platform=Platform.TORRENT, kind=Kind.TORRENT, status=TaskStatus.SEEDING)

    assert await service.resolve_media_file(task_id, 3) == (Path("/t/file3.mkv"), "file3.mkv", 3)
    assert await service.resolve_media_file(task_id, None) == (Path("/t/file7.mkv"), "file7.mkv", 7)


def _torrent_task(repo: FakeRepo, status: TaskStatus) -> uuid.UUID:
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, platform=Platform.TORRENT, kind=Kind.TORRENT, status=status, info_hash="abc123"
    )
    return task_id


@pytest.mark.asyncio
async def test_a_downloading_torrent_plays_from_its_torrent() -> None:
    """Play mid-download (#95): the task's own torrent and file, nothing added."""
    service, repo, torrents = _service()
    task_id = _torrent_task(repo, TaskStatus.DOWNLOADING)

    assert await service.torrent_play(task_id, None) == TorrentPlay("abc123", 7)
    assert await service.torrent_play(task_id, 2) == TorrentPlay("abc123", 2)
    assert torrents.resumed == []


@pytest.mark.asyncio
async def test_a_paused_torrent_is_resumed_to_play() -> None:
    """A stream from a paused torrent would stall."""
    service, repo, torrents = _service()
    task_id = _torrent_task(repo, TaskStatus.PAUSED)

    assert await service.torrent_play(task_id, None) == TorrentPlay("abc123", 7)
    assert torrents.resumed == [task_id]


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [TaskStatus.COMPLETE, TaskStatus.SEEDING])
async def test_a_finished_torrent_plays_from_disk(status: TaskStatus) -> None:
    service, repo, _ = _service()

    assert await service.torrent_play(_torrent_task(repo, status), None) is None


@pytest.mark.asyncio
async def test_a_download_that_is_not_a_torrent_plays_from_disk() -> None:
    service, repo, _ = _service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    assert await service.torrent_play(task_id, None) is None


@pytest.mark.asyncio
async def test_a_failed_torrent_has_nothing_to_play() -> None:
    service, repo, _ = _service()

    with pytest.raises(Error) as caught:
        await service.torrent_play(_torrent_task(repo, TaskStatus.FAILED), None)
    assert caught.value.code == 409


class FakePositionRepo:
    def __init__(self) -> None:
        self.rows: dict[tuple[uuid.UUID, int], Any] = {}
        self.batches: list[list[uuid.UUID]] = []

    async def save(
        self, task_id: uuid.UUID, file_index: int, *, position_seconds: float, duration_seconds: float,
        watched: bool,
    ) -> Any:
        row = type("P", (), {"task_id": task_id, "file_index": file_index, "position_seconds": position_seconds,
                             "duration_seconds": duration_seconds, "watched": watched})()
        self.rows[(task_id, file_index)] = row
        return row

    async def list_for_tasks(self, task_ids: Any) -> dict[uuid.UUID, list[Any]]:
        self.batches.append(list(task_ids))
        return {
            task_id: sorted((r for (t, _), r in self.rows.items() if t == task_id), key=lambda r: r.file_index)
            for task_id in task_ids
        }


class _SchemaTorrents(FakeTorrentService):
    """Real schemas, so positions can be attached to them."""

    async def schemas(self, tasks: Any) -> list[Any]:
        from src.data.schema.download import TaskSchema

        return [TaskSchema.model_validate(task) for task in tasks]


def _positions_service() -> tuple[DownloadService, FakeRepo, FakePositionRepo]:
    repo, positions = FakeRepo(), FakePositionRepo()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        segment_repo=FakeSegmentRepo(),  # ty: ignore[invalid-argument-type]
        client=FakeSiteClient(site_info("youtube")),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=Path("/tmp/anydm-test"),
        torrents=_SchemaTorrents(),  # ty: ignore[invalid-argument-type]
        positions=positions,  # ty: ignore[invalid-argument-type]
    )
    return service, repo, positions


@pytest.mark.asyncio
async def test_a_position_is_saved_for_a_tasks_file() -> None:
    """Where a download was left, so it resumes on any device (#96)."""
    service, repo, positions = _positions_service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    saved = await service.save_position(task_id, None, position_seconds=61.5, duration_seconds=1300.0)

    assert (saved.file_index, saved.position_seconds, saved.watched) == (0, 61.5, False)
    assert positions.rows[(task_id, 0)].position_seconds == 61.5


@pytest.mark.asyncio
async def test_stopping_near_the_end_marks_it_watched_and_clears_where_to_resume() -> None:
    service, repo, _ = _positions_service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    saved = await service.save_position(task_id, 2, position_seconds=1275.0, duration_seconds=1300.0)

    assert (saved.position_seconds, saved.watched) == (0.0, True)


@pytest.mark.asyncio
async def test_a_watched_file_stays_watched_when_played_again() -> None:
    service, repo, _ = _positions_service()
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)
    await service.save_position(task_id, 0, position_seconds=1290.0, duration_seconds=1300.0)

    again = await service.save_position(task_id, 0, position_seconds=40.0, duration_seconds=1300.0)

    assert (again.position_seconds, again.watched) == (40.0, True)


@pytest.mark.asyncio
async def test_a_position_for_a_task_that_is_gone_is_a_404() -> None:
    service, _, _ = _positions_service()

    with pytest.raises(Error) as caught:
        await service.save_position(uuid.uuid4(), None, position_seconds=1.0, duration_seconds=2.0)
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_tasks_carry_their_positions_a_page_at_a_time() -> None:
    service, repo, positions = _positions_service()
    first, second = uuid.uuid4(), uuid.uuid4()
    repo.page = [_row(first), _row(second)]
    repo.rows[first] = repo.page[0]
    await service.save_position(first, 1, position_seconds=10.0, duration_seconds=100.0)
    positions.batches.clear()

    tasks, _ = await service.list_tasks(page=1, page_size=20)

    assert positions.batches == [[first, second]]
    assert [(p.file_index, p.position_seconds) for p in tasks[0].positions or []] == [(1, 10.0)]
    assert tasks[1].positions == []
    one = await service.get_task(first)
    assert [p.file_index for p in one.positions or []] == [1]


def test_every_bulk_action_the_api_accepts_has_a_scope() -> None:
    """The names live in the type module; what they mean lives in the service."""
    from typing import get_args

    from src.data.type import BulkAction
    from src.service.download.download_service import BULK_SCOPES

    assert set(get_args(BulkAction)) == set(BULK_SCOPES)


@pytest.mark.asyncio
async def test_a_download_s_subtitle_files_are_its_neighbours_on_disk(tmp_path: Path) -> None:
    """#101: beside it, or in a subtitles folder there; never another film's."""
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    folder = tmp_path / str(task_id)
    (folder / "Subs").mkdir(parents=True)
    for name in ("Movie.mkv", "Movie.en.srt", "Subs/Movie.fr.srt", "Other.en.srt"):
        (folder / name).write_bytes(b"x")
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/Movie.mkv", filename="Movie.mkv"
    )

    found = await service.subtitle_files(task_id, None)

    assert [(sidecar.path, source) for sidecar, source in found] == [
        ("Movie.en.srt", folder / "Movie.en.srt"),
        ("Subs/Movie.fr.srt", folder / "Subs" / "Movie.fr.srt"),
    ]


@pytest.mark.asyncio
async def test_an_unfinished_download_has_no_subtitle_files_yet(tmp_path: Path) -> None:
    service, repo, _ = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING, file_path=None)

    assert await service.subtitle_files(task_id, None) == []
