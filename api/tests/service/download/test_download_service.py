import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.youtube.protocol import StreamInfo, VideoInfo
from src.service.download.control import DownloadControl
from src.service.download.download_service import DownloadService

VIDEO_ID = "dQw4w9WgXcQ"


class FakeClient:
    async def fetch_info(self, video_id: str) -> VideoInfo:
        return VideoInfo(
            video_id=video_id,
            title="Never Gonna Give You Up",
            streams=[
                StreamInfo(itag=137, mime_type="video/mp4", quality="1080p", height=1080, has_video=True),
                StreamInfo(itag=140, mime_type="audio/mp4", bitrate=128000, has_audio=True),
            ],
        )

    async def stream_url(self, video_id: str, itag: int) -> str:
        raise AssertionError("enqueue must not resolve stream URLs")


class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.rows: dict[uuid.UUID, Any] = {}

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()

    async def get_active_by_id(self, task_id: uuid.UUID) -> Any:
        return self.rows.get(task_id)


def _row(task_id: uuid.UUID, **overrides: Any) -> Any:
    """A stand-in for a Task row, with a no-op ``save``."""
    fields: dict[str, Any] = {
        "id": task_id,
        "source_url": "https://youtu.be/x",
        "platform": Platform.YOUTUBE,
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


def _service(downloads_dir: Path | None = None) -> tuple[DownloadService, FakeRepo]:
    repo = FakeRepo()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        segment_repo=FakeSegmentRepo(),  # ty: ignore[invalid-argument-type]
        client=FakeClient(),
        control=DownloadControl(),
        hub=EventHub(),
        downloads_root=downloads_dir or Path("/tmp/anydm-test"),
    )
    return service, repo


@pytest.mark.asyncio
async def test_enqueue_writes_a_pending_row() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    assert len(repo.created) == 1
    row = repo.created[0]
    assert row["status"] == TaskStatus.PENDING
    assert row["platform"] == Platform.YOUTUBE
    assert row["video_id"] == VIDEO_ID
    assert row["preset"] == Preset.P1080
    assert row["progress"] == 0


@pytest.mark.asyncio
async def test_enqueue_stores_the_resolved_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    row = repo.created[0]
    assert row["kind"] == Kind.VIDEO
    assert row["video_itag"] == 137
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up_1080p.mp4"
    assert row["title"] == "Never Gonna Give You Up"


@pytest.mark.asyncio
async def test_enqueue_stores_an_mp3_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.MP3)

    row = repo.created[0]
    assert row["kind"] == Kind.AUDIO
    assert row["video_itag"] is None
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up.mp3"


@pytest.mark.asyncio
async def test_enqueue_rejects_a_non_youtube_url() -> None:
    service, _ = _service()
    with pytest.raises(Error) as caught:
        await service.enqueue_youtube("https://example.com/v", Preset.BEST)
    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_a_taller_preset_than_available_falls_back_to_the_tallest() -> None:
    # 1080p is the tallest on offer, so 2160 degrades rather than failing.
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P2160)
    assert repo.created[0]["video_itag"] == 137


@pytest.mark.asyncio
async def test_resolve_file_rejects_an_incomplete_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING, file_path=None)

    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_resolve_file_returns_the_path_for_a_complete_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
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
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.resolve_file(uuid.uuid4())
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_file_vanished(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/gone.mp4", filename="gone.mp4"
    )
    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_pause_stops_a_running_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    result = await service.pause(task_id)

    assert result.status == TaskStatus.PAUSED
    assert service._control.is_stopping(task_id) is True


@pytest.mark.asyncio
async def test_pause_also_works_on_a_queued_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PENDING)
    assert (await service.pause(task_id)).status == TaskStatus.PAUSED


@pytest.mark.asyncio
async def test_pause_rejects_a_completed_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    with pytest.raises(Error) as caught:
        await service.pause(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_resume_requeues_a_paused_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PAUSED)
    service._control.request_stop(task_id)

    result = await service.resume(task_id)

    assert result.status == TaskStatus.PENDING
    assert service._control.is_stopping(task_id) is False


@pytest.mark.asyncio
async def test_resume_clears_the_failure_state(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
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
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await service.resume(task_id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_cancel_stops_the_task_and_removes_its_files(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    (tmp_path / str(task_id)).mkdir(parents=True)
    (tmp_path / str(task_id) / "video.part").write_bytes(b"x")
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    await service.cancel(task_id)

    assert not (tmp_path / str(task_id)).exists()
    assert repo.rows[task_id].status == TaskStatus.CANCELED
    assert repo.rows[task_id].deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_404s_on_an_unknown_task(tmp_path: Path) -> None:
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.cancel(uuid.uuid4())
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_enqueue_url_writes_a_direct_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    await service.enqueue_url("https://cdn.test/files/report.pdf")

    row = repo.created[0]
    assert row["platform"] == Platform.DIRECT
    assert row["kind"] == Kind.FILE
    assert row["filename"] == "report.pdf"
    assert row["video_itag"] is None
    assert row["audio_itag"] is None
    assert row["video_id"] is None
    assert row["status"] == TaskStatus.PENDING


@pytest.mark.asyncio
async def test_enqueue_url_rejects_a_non_http_scheme(tmp_path: Path) -> None:
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.enqueue_url("file:///etc/passwd")
    assert caught.value.code == 400
