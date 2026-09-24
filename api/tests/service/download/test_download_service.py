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
from src.service.download.download_service import DownloadService

from tests.sites import FakeSiteClient, site_info, sized

YOUTUBE = "https://youtu.be/dQw4w9WgXcQ"


class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.rows: dict[uuid.UUID, Any] = {}
        self.listed_statuses: list[Any] | None = None
        self.listed_sort: str | None = None

    async def list_page(
        self,
        page: int,
        page_size: int,
        statuses: list[Any] | None = None,
        sort: str = "-created_at",
    ) -> tuple[list[Any], Meta]:
        self.listed_statuses = statuses
        self.listed_sort = sort
        return [], Meta(page=page, page_size=page_size, total=0, total_pages=0)

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
async def test_a_site_with_only_streaming_formats_is_refused_until_they_are_supported() -> None:
    service, repo, _ = _service(client=FakeSiteClient(site_info("dailymotion")))

    with pytest.raises(Error) as caught:
        await service.enqueue_media("https://dailymotion.com/video/x", Preset.BEST)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert "streaming formats" in (caught.value.message or "")
    assert repo.created == []


@pytest.mark.asyncio
async def test_https_formats_are_chosen_over_taller_streaming_ones_for_now() -> None:
    # Reddit's tallest is HLS-only at 640p; its HTTPS formats stop at 480p.
    service, repo, _ = _service(client=FakeSiteClient(site_info("reddit")))
    await service.enqueue_media("https://reddit.com/r/x", Preset.BEST)

    assert (repo.created[0]["video_format"], repo.created[0]["audio_format"]) == ("dash-VIDEO-1", "dash-AUDIO-1")


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


def test_every_bulk_action_the_api_accepts_has_a_scope() -> None:
    """The names live in the type module; what they mean lives in the service."""
    from typing import get_args

    from src.data.type import BulkAction
    from src.service.download.download_service import BULK_SCOPES

    assert set(get_args(BulkAction)) == set(BULK_SCOPES)
