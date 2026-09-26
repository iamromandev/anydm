import asyncio
import math
import uuid
from collections.abc import Callable, Mapping
from pathlib import Path

import httpx
import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.media.audio import AudioTrack
from src.lib.media.ffprobe import ProbeResult
from src.lib.media.sidecar import Sidecar, TorrentFile
from src.lib.media.source import MediaInput
from src.lib.media.subtitle import SubtitleTrack
from src.lib.site import error as site_error
from src.lib.site.client import SiteInfo
from src.lib.site.format import Format, playback_plan
from src.lib.site.subtitles import SiteSubtitle
from src.lib.torrent.protocol import FileInfo, TorrentDetails, TorrentProgress
from src.lib.torrent.source import TorrentSource
from src.service.download.download_service import TorrentPlay
from src.service.stream.session import SegmentState, SiteOrigin, StreamSessionStore
from src.service.stream.stream_service import Prober, StreamService, fetch_playlist

from tests.sites import HEADERS, FakeSiteClient, media_url, site_info


def _service(tmp_path: Path, **overrides: object) -> tuple[StreamService, list[list[str]]]:
    encoded_calls: list[list[str]] = []

    async def fake_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def fake_encoder(args: list[str]) -> None:
        encoded_calls.append(args)
        Path(args[-1]).write_bytes(b"fake-ts-data")

    defaults: dict[str, object] = {
        "sessions": StreamSessionStore(),
        "stream_dir": tmp_path,
        "ffmpeg_path": "ffmpeg",
        "ffprobe_path": "ffprobe",
        "segment_seconds": 6,
        "readahead_segments": 2,
        "max_concurrent_encodes": 2,
        "prober": fake_prober,
        "encoder": fake_encoder,
    }
    defaults.update(overrides)
    return StreamService(**defaults), encoded_calls  # ty: ignore[invalid-argument-type]


@pytest.mark.asyncio
async def test_start_session_probes_and_creates_a_session_dir(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    assert session.duration_seconds == 20.0
    assert session.has_video is True
    assert session.segment_count == 4
    assert session.session_dir.exists()
    assert service.get_session(session.id) is session


@pytest.mark.asyncio
async def test_get_session_raises_not_found_for_an_unknown_id(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    with pytest.raises(Error):
        service.get_session("nope")


@pytest.mark.asyncio
async def test_playlist_text_lists_every_segment_with_the_right_durations(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    playlist = service.playlist_text(session)

    assert "#EXTM3U" in playlist
    assert "#EXT-X-ENDLIST" in playlist
    assert "segment_0.ts" in playlist
    assert "segment_3.ts" in playlist
    assert "segment_4.ts" not in playlist
    assert "#EXTINF:2.000," in playlist  # the shorter final segment


@pytest.mark.asyncio
async def test_playlist_text_marks_every_segment_after_the_first_as_a_discontinuity(
    tmp_path: Path,
) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    lines = service.playlist_text(session).splitlines()

    # segment_0.ts is the start of the stream and needs no discontinuity
    # marker; every segment after it is an independently-encoded file whose
    # raw timestamps restart near zero, so each needs one.
    assert lines[lines.index("segment_0.ts") - 2] != "#EXT-X-DISCONTINUITY"
    for name in ["segment_1.ts", "segment_2.ts", "segment_3.ts"]:
        assert lines[lines.index(name) - 2] == "#EXT-X-DISCONTINUITY"


@pytest.mark.asyncio
async def test_get_segment_generates_and_returns_the_file(tmp_path: Path) -> None:
    service, encoded_calls = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    path = await service.get_segment(session, 0)

    assert path == session.segment_path(0)
    assert path.exists()
    assert session.state_of(0) == SegmentState.READY
    assert len(encoded_calls) >= 1


@pytest.mark.asyncio
async def test_get_segment_rejects_an_out_of_range_index(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    with pytest.raises(Error):
        await service.get_segment(session, 999)


@pytest.mark.asyncio
async def test_concurrent_requests_for_the_same_segment_encode_only_once(tmp_path: Path) -> None:
    encode_started = asyncio.Event()
    release_encode = asyncio.Event()
    calls: list[list[str]] = []

    async def slow_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def slow_encoder(args: list[str]) -> None:
        calls.append(args)
        encode_started.set()
        await release_encode.wait()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service = StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=slow_prober,
        encoder=slow_encoder,
    )
    session = await service.start_session("http://example.com/movie.mkv")

    task_a = asyncio.create_task(service.get_segment(session, 0))
    await encode_started.wait()
    task_b = asyncio.create_task(service.get_segment(session, 0))
    await asyncio.sleep(0.01)  # let task_b reach the GENERATING branch and start waiting
    release_encode.set()

    await asyncio.gather(task_a, task_b)
    assert len(calls) == 1


def _encode_failure() -> Error:
    return Error.create(
        code=Code.INTERNAL_SERVER_ERROR, message="ffmpeg exited with 1", error_type=ErrorType.DEPENDENCY_FAILURE
    )


@pytest.mark.asyncio
async def test_a_segment_whose_encode_failed_is_encoded_again_when_asked_again(tmp_path: Path) -> None:
    # Regression (#81): a failed encode left the segment GENERATING for good,
    # so the player's retry waited on an event nothing would ever set.
    calls: list[list[str]] = []

    async def fails_once(args: list[str]) -> None:
        calls.append(args)
        if len(calls) == 1:
            raise _encode_failure()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service, _ = _service(tmp_path, encoder=fails_once, readahead_segments=0)
    session = await service.start_session("http://example.com/movie.mkv")

    with pytest.raises(Error):
        await service.get_segment(session, 0)
    path = await asyncio.wait_for(service.get_segment(session, 0), timeout=1.0)

    assert path.exists()
    assert session.state_of(0) == SegmentState.READY
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_a_request_waiting_on_a_failed_encode_tries_again_rather_than_hanging(tmp_path: Path) -> None:
    first_started = asyncio.Event()
    release_first = asyncio.Event()
    calls: list[list[str]] = []

    async def first_fails_late(args: list[str]) -> None:
        calls.append(args)
        if len(calls) == 1:
            first_started.set()
            await release_first.wait()
            raise _encode_failure()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service, _ = _service(tmp_path, encoder=first_fails_late, readahead_segments=0)
    session = await service.start_session("http://example.com/movie.mkv")

    first = asyncio.create_task(service.get_segment(session, 0))
    await first_started.wait()
    waiting = asyncio.create_task(service.get_segment(session, 0))
    await asyncio.sleep(0.01)  # let it reach the GENERATING branch and wait
    release_first.set()

    with pytest.raises(Error):
        await first
    path = await asyncio.wait_for(waiting, timeout=1.0)

    assert path.exists()
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_requests_waiting_on_a_failed_encode_share_one_retry(tmp_path: Path) -> None:
    # The retry gets a fresh event: the failed attempt's is already set, and a
    # second waiter handed that one would never wait for the retry at all.
    releases = [asyncio.Event(), asyncio.Event()]
    started = [asyncio.Event(), asyncio.Event()]
    calls: list[list[str]] = []

    async def fails_then_succeeds(args: list[str]) -> None:
        attempt = len(calls)
        calls.append(args)
        started[attempt].set()
        await releases[attempt].wait()
        if attempt == 0:
            raise _encode_failure()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service, _ = _service(tmp_path, encoder=fails_then_succeeds, readahead_segments=0)
    session = await service.start_session("http://example.com/movie.mkv")

    first = asyncio.create_task(service.get_segment(session, 0))
    await started[0].wait()
    waiters = [asyncio.create_task(service.get_segment(session, 0)) for _ in range(2)]
    await asyncio.sleep(0.01)
    releases[0].set()
    with pytest.raises(Error):
        await first
    await asyncio.wait_for(started[1].wait(), timeout=1.0)
    releases[1].set()

    paths = await asyncio.wait_for(asyncio.gather(*waiters), timeout=1.0)
    assert all(path.exists() for path in paths)
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_a_cancelled_read_ahead_leaves_its_segment_to_be_encoded(tmp_path: Path) -> None:
    started = asyncio.Event()
    calls: list[list[str]] = []

    async def hangs_first(args: list[str]) -> None:
        calls.append(args)
        if len(calls) == 1:
            started.set()
            await asyncio.Event().wait()  # only cancellation ends this
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service, _ = _service(tmp_path, encoder=hangs_first, readahead_segments=0)
    session = await service.start_session("http://example.com/movie.mkv")

    read_ahead = asyncio.create_task(service.get_segment(session, 1))
    await started.wait()
    read_ahead.cancel()
    with pytest.raises(asyncio.CancelledError):
        await read_ahead

    path = await asyncio.wait_for(service.get_segment(session, 1), timeout=1.0)
    assert path.exists()


@pytest.mark.asyncio
async def test_requesting_a_segment_triggers_readahead(tmp_path: Path) -> None:
    service, _encoded_calls = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    await service.get_segment(session, 0)
    await asyncio.gather(*session.background_tasks)

    assert session.state_of(1) == SegmentState.READY
    assert session.state_of(2) == SegmentState.READY
    assert session.state_of(3) == SegmentState.NOT_STARTED  # readahead is 2, not the whole file


@pytest.mark.asyncio
async def test_stop_session_removes_it_and_deletes_its_directory(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    await service.get_segment(session, 0)
    session_dir = session.session_dir

    await service.stop_session(session.id)

    assert not session_dir.exists()
    with pytest.raises(Error):
        service.get_session(session.id)


@pytest.mark.asyncio
async def test_stop_session_on_an_unknown_id_is_a_no_op(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    await service.stop_session("nope")  # must not raise


@pytest.mark.asyncio
async def test_stop_session_does_not_hang_on_a_still_running_readahead_encode(
    tmp_path: Path,
) -> None:
    # Regression: stop_session used to fire-and-forget cancel() without
    # awaiting the cancelled tasks, then immediately rmtree the directory
    # those tasks were still writing into. On a slow filesystem that can hang
    # the whole process. The fake encoder here never returns on its own —
    # only cancellation ends it — so this proves stop_session actually waits.
    async def fake_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    never_finishes = asyncio.Event()

    async def hanging_encoder(args: list[str]) -> None:
        destination = Path(args[-1])
        destination.touch()
        # segment_0 (the directly-awaited one) must complete normally so
        # get_segment() returns and the readahead tasks actually get a
        # chance to start; only the readahead segments hang.
        if destination.name != "segment_0.ts":
            await never_finishes.wait()  # only cancellation ends this

    service = StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=2,
        max_concurrent_encodes=2,
        prober=fake_prober,
        encoder=hanging_encoder,
    )
    session = await service.start_session("http://example.com/movie.mkv")

    await service.get_segment(session, 0)  # kicks off readahead for 1 and 2
    await asyncio.sleep(0.01)  # let the readahead tasks actually start

    await asyncio.wait_for(service.stop_session(session.id), timeout=2.0)

    assert all(task.cancelled() for task in session.background_tasks)


class FakeTorrentClient:
    def __init__(self, *, details: TorrentDetails, fail: Error | None = None) -> None:
        self.details = details
        self.fail = fail
        self.added: list[dict[str, object]] = []
        self.deleted: list[str] = []
        self.progress_rows: list[TorrentProgress] = []

    async def resolve(self, source: TorrentSource) -> TorrentDetails:
        if self.fail:
            raise self.fail
        return self.details

    async def add(self, source: TorrentSource, *, only_files, output_folder: str) -> TorrentDetails:
        self.added.append({"only_files": list(only_files), "output_folder": output_folder})
        return self.details

    async def list_progress(self) -> list[TorrentProgress]:
        return self.progress_rows

    async def delete(self, info_hash: str) -> None:
        if self.fail:
            raise self.fail
        self.deleted.append(info_hash)


class FakeTaskRepo:
    def __init__(self, *, existing_info_hash: str | None = None) -> None:
        self._existing_info_hash = existing_info_hash

    async def get_one(self, **kwargs: object) -> object | None:
        if kwargs.get("info_hash") == self._existing_info_hash and self._existing_info_hash is not None:
            return object()  # any truthy row stands in for a real Task
        return None


TORRENT_DETAILS = TorrentDetails(
    info_hash="deadbeef",
    name="Some Release",
    output_folder="/workdir/download/torrent/Some Release",
    files=[
        FileInfo(index=0, path="Movie.en.srt", size_bytes=100),
        FileInfo(index=1, path="Movie.mkv", size_bytes=900_000_000),
    ],
)


def _torrent_service(
    tmp_path: Path,
    *,
    torrent_client: object,
    task_repo: object,
    prober: Prober | None = None,
) -> tuple[StreamService, list[list[str]]]:
    encoded_calls: list[list[str]] = []

    async def default_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def fake_encoder(args: list[str]) -> None:
        encoded_calls.append(args)
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service = StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=prober or default_prober,
        encoder=fake_encoder,
        torrent_client=torrent_client,  # ty: ignore[invalid-argument-type]
        task_repo=task_repo,  # ty: ignore[invalid-argument-type]
        torrent_dir=tmp_path / "torrent",
        torrent_api_url="http://torrent-anydm-api:3030",
        torrent_enabled=True,
    )
    return service, encoded_calls


@pytest.mark.asyncio
async def test_start_torrent_session_resolves_picks_and_adds(tmp_path: Path) -> None:
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    service, _ = _torrent_service(tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo())

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")
    await asyncio.gather(*session.background_tasks)

    assert session.info_hash == "deadbeef"
    assert session.status == "ready"
    assert session.duration_seconds == 20.0  # from the fake prober
    # Its own folder: a streamed file must not overwrite a download's (#107),
    # with the film's subtitle file beside it (#101).
    assert torrent_client.added == [
        {"only_files": [1, 0], "output_folder": str(tmp_path / "torrent" / "Some Release")}
    ]


SEASON_DETAILS = TorrentDetails(
    info_hash="5ea50ea5",
    name="Show S01",
    output_folder="/workdir/download/torrent/Show S01",
    files=[
        FileInfo(index=0, path="Show.S01E10.mkv", size_bytes=900),
        FileInfo(index=1, path="Show.S01E2.mkv", size_bytes=800),
        FileInfo(index=2, path="Show.S01E2.en.srt", size_bytes=10),
    ],
)


@pytest.mark.asyncio
async def test_start_torrent_session_plays_the_file_asked_for(tmp_path: Path) -> None:
    """Episode 2, not whichever is biggest (#98)."""
    torrent_client = FakeTorrentClient(details=SEASON_DETAILS)
    service, _ = _torrent_service(tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo())

    session = await service.start_torrent_session("magnet:?xt=urn:btih:5ea50ea5", file_index=1)
    await asyncio.gather(*session.background_tasks)

    # Episode 2's subtitle file too, never episode 10's (#101).
    assert torrent_client.added[0]["only_files"] == [1, 2]
    assert session.inputs[0].url == "http://torrent-anydm-api:3030/torrents/5ea50ea5/stream/1"


@pytest.mark.asyncio
@pytest.mark.parametrize("file_index", [2, 9])
async def test_start_torrent_session_refuses_a_file_that_is_not_media(tmp_path: Path, file_index: int) -> None:
    torrent_client = FakeTorrentClient(details=SEASON_DETAILS)
    service, _ = _torrent_service(tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo())

    with pytest.raises(Error) as caught:
        await service.start_torrent_session("magnet:?xt=urn:btih:5ea50ea5", file_index=file_index)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert torrent_client.added == []


@pytest.mark.asyncio
async def test_start_torrent_session_returns_immediately_as_connecting(tmp_path: Path) -> None:
    never_returns = asyncio.Event()

    async def hanging_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        await never_returns.wait()
        raise AssertionError("unreachable")

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=TORRENT_DETAILS),
        task_repo=FakeTaskRepo(),
        prober=hanging_prober,
    )

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    assert session.status == "connecting"
    assert session.duration_seconds == 0.0
    await service.stop_session(session.id)  # let the hanging background task be cancelled cleanly


@pytest.mark.asyncio
async def test_start_torrent_session_builds_the_rqbit_stream_url(tmp_path: Path) -> None:
    calls: list[str] = []

    async def recording_prober(_ffprobe: str, source: str, **_: object) -> ProbeResult:
        calls.append(source)
        return ProbeResult(duration_seconds=20.0, has_video=True)

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=TORRENT_DETAILS),
        task_repo=FakeTaskRepo(),
        prober=recording_prober,
    )

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")
    await asyncio.gather(*session.background_tasks)

    assert calls == ["http://torrent-anydm-api:3030/torrents/deadbeef/stream/1"]


@pytest.mark.asyncio
async def test_start_torrent_session_publishes_peer_progress_while_connecting(
    tmp_path: Path,
) -> None:
    from src.lib.event import EventHub

    hub = EventHub()
    subscription = hub.subscribe()
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    torrent_client.progress_rows = [
        TorrentProgress(
            info_hash="deadbeef",
            state="live",
            finished=False,
            progress_bytes=0,
            uploaded_bytes=0,
            total_bytes=900_000_000,
            download_bps=340_000,
            upload_bps=0,
            peers_connected=2,
        )
    ]

    probe_started = asyncio.Event()
    release_probe = asyncio.Event()

    async def slow_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        probe_started.set()
        await release_probe.wait()
        return ProbeResult(duration_seconds=20.0, has_video=True)

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=torrent_client,
        task_repo=FakeTaskRepo(),
        prober=slow_prober,
    )
    service._event_hub = hub
    service._progress_poll_s = 0.01

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")
    await probe_started.wait()

    event, data = await asyncio.wait_for(subscription.__aiter__().__anext__(), timeout=1.0)
    assert event == "stream_status"
    assert data["id"] == session.id
    assert data["status"] == "connecting"
    assert data["peers_connected"] == 2
    assert data["download_bps"] == 340_000

    release_probe.set()
    await asyncio.gather(*session.background_tasks)


@pytest.mark.asyncio
async def test_progress_polling_survives_past_ready_and_stops_when_session_stops(
    tmp_path: Path,
) -> None:
    from src.lib.event import EventHub

    hub = EventHub()
    subscription = hub.subscribe()
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    torrent_client.progress_rows = [
        TorrentProgress(
            info_hash="deadbeef",
            state="live",
            finished=False,
            progress_bytes=450_000_000,
            uploaded_bytes=0,
            total_bytes=900_000_000,
            download_bps=340_000,
            upload_bps=0,
            peers_connected=2,
        )
    ]

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=torrent_client,
        task_repo=FakeTaskRepo(),
    )
    service._event_hub = hub
    service._progress_poll_s = 0.01

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    events = subscription.__aiter__()
    data: dict[str, object] = {}
    for _ in range(200):
        _, data = await asyncio.wait_for(events.__anext__(), timeout=1.0)
        if data["id"] == session.id and "peers_connected" in data and data["status"] == "ready":
            break
    else:
        raise AssertionError("never saw a post-ready progress event")

    assert data["peers_connected"] == 2
    assert data["progress_bytes"] == 450_000_000
    assert data["total_bytes"] == 900_000_000
    assert session.progress_task is not None
    assert not session.progress_task.done()

    await service.stop_session(session.id)
    assert session.progress_task.cancelled()


@pytest.mark.asyncio
async def test_start_torrent_session_marks_status_error_when_probe_fails(tmp_path: Path) -> None:
    async def failing_prober(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        raise Error.create(
            code=Code.REQUEST_TIMEOUT,
            message="ffprobe did not finish within 600s",
            error_type=ErrorType.TIMEOUT,
        )

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=TORRENT_DETAILS),
        task_repo=FakeTaskRepo(),
        prober=failing_prober,
    )
    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    await asyncio.gather(*session.background_tasks)

    assert session.status == "error"
    assert session.error == "ffprobe did not finish within 600s"


@pytest.mark.asyncio
async def test_start_torrent_session_rejects_a_torrent_with_no_media_file(tmp_path: Path) -> None:
    no_media = TorrentDetails(
        info_hash="deadbeef",
        name="Docs",
        output_folder="/x",
        files=[FileInfo(index=0, path="readme.txt", size_bytes=100)],
    )
    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=no_media),
        task_repo=FakeTaskRepo(),
    )
    with pytest.raises(Error):
        await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")


@pytest.mark.asyncio
async def test_start_torrent_session_rejects_when_torrent_support_is_disabled(tmp_path: Path) -> None:
    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=TORRENT_DETAILS),
        task_repo=FakeTaskRepo(),
    )
    service._torrent_enabled = False
    with pytest.raises(Error):
        await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")


@pytest.mark.asyncio
async def test_stop_session_deletes_the_torrent_when_no_task_owns_it(tmp_path: Path) -> None:
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    service, _ = _torrent_service(tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo())
    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    await service.stop_session(session.id)

    assert torrent_client.deleted == ["deadbeef"]


@pytest.mark.asyncio
async def test_stop_session_leaves_the_torrent_when_a_real_task_owns_it(tmp_path: Path) -> None:
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    service, _ = _torrent_service(
        tmp_path,
        torrent_client=torrent_client,
        task_repo=FakeTaskRepo(existing_info_hash="deadbeef"),
    )
    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    await service.stop_session(session.id)

    assert torrent_client.deleted == []


@pytest.mark.asyncio
async def test_stop_session_does_not_raise_when_the_engine_delete_fails(tmp_path: Path) -> None:
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    service, _ = _torrent_service(tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo())
    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    torrent_client.fail = Error.service_unavailable("down")
    await service.stop_session(session.id)  # must not raise


# --- pages on a site --------------------------------------------------------------

YOUTUBE_PAGE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
DAILYMOTION_PAGE = "https://www.dailymotion.com/video/x8"
VIMEO_PAGE = "https://vimeo.com/channels/keypeele/75629013"
FORBIDDEN = "ffmpeg exited with 8: [https @ 0x1] HTTP error 403 Forbidden"


def _media_playlist(fragments: int = 10, seconds: float = 2.0, *, name: str = "frag") -> str:
    """A finished media playlist of TS fragments, named relative to wherever it's served."""
    lines = ["#EXTM3U", f"#EXT-X-TARGETDURATION:{math.ceil(seconds)}"]
    for n in range(fragments):
        lines += [f"#EXTINF:{seconds:.3f},", f"{name}{n}.ts"]
    return "\n".join([*lines, "#EXT-X-ENDLIST"]) + "\n"


class FakePlaylists:
    """A ``PlaylistFetcher`` serving one playlist for any URL, unredirected; records each fetch."""

    def __init__(self, text: str | None = None, *, fail: Error | None = None) -> None:
        self.text = text or _media_playlist()
        self.fail = fail
        self.fetched: list[tuple[str, dict[str, str]]] = []

    async def __call__(self, url: str, headers: Mapping[str, str]) -> tuple[str, str]:
        self.fetched.append((url, dict(headers)))
        if self.fail is not None:
            raise self.fail
        return url, self.text


def _hls_only(site: str) -> SiteInfo:
    """A recorded site with its plain files taken away, so the player has to use its HLS."""
    return site_info(site, formats=[f for f in site_info(site).formats if f.hls])


def _ids(info: SiteInfo) -> tuple[str, ...]:
    plan = playback_plan(info.formats)
    return tuple(part.id for part in (plan.video, plan.audio) if part is not None)


def _site_service(
    tmp_path: Path, client: FakeSiteClient, *, encoder: object = None, **overrides: object
) -> tuple[StreamService, list[tuple[str, object]], list[list[str]]]:
    overrides.setdefault("playlist_fetcher", FakePlaylists())
    probed: list[tuple[str, object]] = []
    encoded: list[list[str]] = []

    async def recording_prober(_ffprobe: str, source: str, *, headers: object = None) -> ProbeResult:
        probed.append((source, headers))
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def recording_encoder(args: list[str]) -> None:
        encoded.append(args)
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service, _ = _service(
        tmp_path,
        prober=recording_prober,
        encoder=encoder or recording_encoder,
        site_client=client,
        readahead_segments=0,
        **overrides,
    )
    return service, probed, encoded


def _plan_ids(site: str) -> tuple[str, ...]:
    return _ids(site_info(site))


def _forbidden() -> Error:
    return Error.create(code=Code.INTERNAL_SERVER_ERROR, message=FORBIDDEN, error_type=ErrorType.DEPENDENCY_FAILURE)


@pytest.mark.asyncio
async def test_a_page_link_plays_its_formats_instead_of_probing_the_page(tmp_path: Path) -> None:
    # The 502: ffprobe was handed the watch page itself and exited with 1.
    client = FakeSiteClient(site_info("youtube"))
    service, probed, _ = _site_service(tmp_path, client)

    session = await service.start_session(YOUTUBE_PAGE)

    video, audio = _plan_ids("youtube")
    assert client.opened == [YOUTUBE_PAGE]
    assert session.inputs == [
        MediaInput(media_url("youtube", video), HEADERS),
        MediaInput(media_url("youtube", audio), HEADERS),
    ]
    assert session.origin == SiteOrigin(site_info("youtube").webpage_url, (video, audio))
    assert probed == [(media_url("youtube", video), HEADERS)]
    assert session.playlists == []


@pytest.mark.asyncio
async def test_a_segment_of_a_page_link_reads_both_inputs_with_their_headers(tmp_path: Path) -> None:
    service, _, encoded = _site_service(tmp_path, FakeSiteClient(site_info("youtube")))
    session = await service.start_session(YOUTUBE_PAGE)

    await service.get_segment(session, 1)

    video, audio = _plan_ids("youtube")
    args = encoded[0]
    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-i"] == [
        media_url("youtube", video),
        media_url("youtube", audio),
    ]
    assert args.count("-headers") == 2
    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-ss"] == ["6", "6"]


@pytest.mark.asyncio
async def test_an_audio_only_site_plays_its_audio(tmp_path: Path) -> None:
    service, _, _ = _site_service(tmp_path, FakeSiteClient(site_info("soundcloud")))

    session = await service.start_session("https://soundcloud.com/someone/a-track")

    assert session.inputs == [MediaInput(media_url("soundcloud", "http_mp3_0_0"), HEADERS)]


@pytest.mark.asyncio
async def test_a_link_no_site_claims_plays_as_a_direct_file(tmp_path: Path) -> None:
    client = FakeSiteClient(site_info("youtube"), fail=site_error.unsupported_url("not a site"))
    service, probed, _ = _site_service(tmp_path, client)

    session = await service.start_session("https://files.test/stream?id=42")

    assert session.inputs == [MediaInput("https://files.test/stream?id=42")]
    assert session.origin is None
    assert probed == [("https://files.test/stream?id=42", {})]


@pytest.mark.asyncio
async def test_a_media_file_link_is_not_extracted(tmp_path: Path) -> None:
    client = FakeSiteClient(site_info("youtube"))
    service, _, _ = _site_service(tmp_path, client)

    session = await service.start_session("https://files.test/movie.MKV?token=1")

    assert client.opened == []
    assert session.inputs == [MediaInput("https://files.test/movie.MKV?token=1")]


@pytest.mark.asyncio
async def test_a_live_page_is_refused(tmp_path: Path) -> None:
    service, probed, _ = _site_service(tmp_path, FakeSiteClient(site_info("youtube", is_live=True)))

    with pytest.raises(Error) as caught:
        await service.start_session(YOUTUBE_PAGE)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert probed == []


@pytest.mark.asyncio
async def test_an_hls_only_page_plays_its_hls(tmp_path: Path) -> None:
    service, _, _ = _site_service(tmp_path, FakeSiteClient(site_info("dailymotion")))

    session = await service.start_session(DAILYMOTION_PAGE)

    assert session.inputs == [MediaInput(media_url("dailymotion", "hls-1080"), HEADERS)]


@pytest.mark.asyncio
async def test_an_extraction_failure_is_not_retried_as_a_direct_file(tmp_path: Path) -> None:
    client = FakeSiteClient(site_info("youtube"), fail=site_error.media_unavailable("Video unavailable"))
    service, probed, _ = _site_service(tmp_path, client)

    with pytest.raises(Error) as caught:
        await service.start_session(YOUTUBE_PAGE)

    assert caught.value.code == Code.NOT_FOUND
    assert probed == []


def _stale(session_inputs: list[MediaInput]) -> list[MediaInput]:
    return [MediaInput(source.url.replace("media.test", "stale.test"), source.headers) for source in session_inputs]


@pytest.mark.asyncio
async def test_an_expired_link_is_resolved_again_and_the_segment_retried(tmp_path: Path) -> None:
    encoded: list[list[str]] = []

    async def encoder(args: list[str]) -> None:
        encoded.append(args)
        if any("stale.test" in arg for arg in args):
            raise _forbidden()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    client = FakeSiteClient(site_info("youtube"))
    service, _, _ = _site_service(tmp_path, client, encoder=encoder)
    session = await service.start_session(YOUTUBE_PAGE)
    session.inputs = _stale(session.inputs)

    await service.get_segment(session, 0)

    video, audio = _plan_ids("youtube")
    assert client.resolved == [(site_info("youtube").webpage_url, [video, audio])]
    assert len(encoded) == 2
    assert media_url("youtube", video) in encoded[1]
    assert session.inputs_version == 1
    assert session.state_of(0) == SegmentState.READY


@pytest.mark.asyncio
async def test_a_link_refused_again_after_resolving_is_the_error(tmp_path: Path) -> None:
    calls: list[list[str]] = []

    async def encoder(args: list[str]) -> None:
        calls.append(args)
        raise _forbidden()

    client = FakeSiteClient(site_info("youtube"))
    service, _, _ = _site_service(tmp_path, client, encoder=encoder)
    session = await service.start_session(YOUTUBE_PAGE)

    with pytest.raises(Error) as caught:
        await service.get_segment(session, 0)

    assert FORBIDDEN in (caught.value.message or "")
    assert len(client.resolved) == 1
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_segments_refused_together_share_one_refresh(tmp_path: Path) -> None:
    both_failing = asyncio.Event()
    failing = 0

    async def encoder(args: list[str]) -> None:
        nonlocal failing
        if any("stale.test" in arg for arg in args):
            failing += 1
            if failing == 2:
                both_failing.set()
            await both_failing.wait()
            raise _forbidden()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    client = FakeSiteClient(site_info("youtube"))
    service, _, _ = _site_service(tmp_path, client, encoder=encoder)
    session = await service.start_session(YOUTUBE_PAGE)
    session.inputs = _stale(session.inputs)

    await asyncio.gather(service.get_segment(session, 0), service.get_segment(session, 1))

    assert len(client.resolved) == 1
    assert session.inputs_version == 1


@pytest.mark.asyncio
async def test_a_direct_file_refused_is_the_error_without_asking_a_site(tmp_path: Path) -> None:
    async def encoder(_args: list[str]) -> None:
        raise _forbidden()

    client = FakeSiteClient(site_info("youtube"))
    service, _, _ = _site_service(tmp_path, client, encoder=encoder)
    session = await service.start_session("https://files.test/movie.mp4")

    with pytest.raises(Error):
        await service.get_segment(session, 0)

    assert client.resolved == []


# --- HLS pages ----------------------------------------------------------------


@pytest.mark.asyncio
async def test_an_hls_page_reads_its_playlist_instead_of_probing(tmp_path: Path) -> None:
    playlists = FakePlaylists(_media_playlist(fragments=7))  # 14 s, where the prober would say 20
    client = FakeSiteClient(site_info("dailymotion"))
    service, probed, _ = _site_service(tmp_path, client, playlist_fetcher=playlists)

    session = await service.start_session(DAILYMOTION_PAGE)

    assert playlists.fetched == [(media_url("dailymotion", "hls-1080"), HEADERS)]
    assert probed == []
    assert (session.duration_seconds, session.segment_count, session.has_video) == (14.0, 3, True)
    assert [len(playlist.fragments) for playlist in session.playlists] == [7]


@pytest.mark.asyncio
async def test_an_hls_page_of_audio_alone_plays_as_audio(tmp_path: Path) -> None:
    # Whether there's a picture comes from the plan: nothing probes an HLS page,
    # and the fake prober would have said there was.
    info = _hls_only("soundcloud")
    service, _, _ = _site_service(tmp_path, FakeSiteClient(info))

    session = await service.start_session("https://soundcloud.com/someone/a-track")

    assert session.has_video is False
    assert session.inputs == [MediaInput(media_url("soundcloud", _ids(info)[0]), HEADERS)]


@pytest.mark.asyncio
async def test_each_input_of_an_hls_page_reads_its_own_playlist(tmp_path: Path) -> None:
    info = _hls_only("vimeo")
    playlists = FakePlaylists()
    service, _, _ = _site_service(tmp_path, FakeSiteClient(info), playlist_fetcher=playlists)

    session = await service.start_session(VIMEO_PAGE)

    video, audio = _ids(info)
    assert playlists.fetched == [(media_url("vimeo", video), HEADERS), (media_url("vimeo", audio), HEADERS)]
    assert len(session.playlists) == 2


@pytest.mark.asyncio
async def test_an_hls_playlist_that_will_not_load_leaves_no_session_behind(tmp_path: Path) -> None:
    store = StreamSessionStore()
    playlists = FakePlaylists(fail=site_error.playlist_failed("status 403"))
    client = FakeSiteClient(site_info("dailymotion"))
    service, _, _ = _site_service(tmp_path, client, playlist_fetcher=playlists, sessions=store)

    with pytest.raises(Error) as caught:
        await service.start_session(DAILYMOTION_PAGE)

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)
    assert store.all() == []
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("text", "message"),
    [
        ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n", "can still be downloaded"),
        ("#EXTM3U\n#EXTINF:2,\nfrag0.ts\n", "Live streams"),
    ],
    ids=["master", "live"],
)
async def test_an_hls_playlist_the_player_cannot_cut_is_refused(tmp_path: Path, text: str, message: str) -> None:
    client = FakeSiteClient(site_info("dailymotion"))
    service, _, _ = _site_service(tmp_path, client, playlist_fetcher=FakePlaylists(text))

    with pytest.raises(Error) as caught:
        await service.start_session(DAILYMOTION_PAGE)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert message in (caught.value.message or "")


@pytest.mark.asyncio
async def test_a_segment_of_an_hls_page_is_cut_from_a_playlist_per_input(tmp_path: Path) -> None:
    service, _, encoded = _site_service(tmp_path, FakeSiteClient(_hls_only("vimeo")))
    session = await service.start_session(VIMEO_PAGE)

    await service.get_segment(session, 1)

    args = encoded[0]
    cuts = [session.session_dir / "segment_1.0.m3u8", session.session_dir / "segment_1.1.m3u8"]
    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-i"] == [str(cut) for cut in cuts]
    for cut in cuts:
        # Segment 1 is 6 to 12 s: fragments 3 to 5, of 2 s each.
        fragments = [line for line in cut.read_text().splitlines() if not line.startswith("#")]
        assert fragments == [f"https://media.test/vimeo/frag{n}.ts" for n in (3, 4, 5)]
    assert "-headers" not in args


@pytest.mark.asyncio
async def test_an_hls_fragment_refused_reads_the_page_and_its_playlist_again(tmp_path: Path) -> None:
    # The fragments' URLs are in the playlist, so a fresh page URL alone would
    # cut the next attempt from the same expired fragments.
    playlists = FakePlaylists(_media_playlist(name="stale"))

    async def encoder(args: list[str]) -> None:
        if "stale" in Path(args[args.index("-i") + 1]).read_text():
            raise _forbidden()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    client = FakeSiteClient(site_info("dailymotion"))
    service, _, _ = _site_service(tmp_path, client, encoder=encoder, playlist_fetcher=playlists)
    session = await service.start_session(DAILYMOTION_PAGE)
    playlists.text = _media_playlist(name="fresh")

    await service.get_segment(session, 1)

    assert client.resolved == [(site_info("dailymotion").webpage_url, ["hls-1080"])]
    assert len(playlists.fetched) == 2
    assert session.inputs_version == 1
    assert session.state_of(1) == SegmentState.READY
    assert "https://media.test/dailymotion/fresh3.ts" in (session.session_dir / "segment_1.0.m3u8").read_text()


# --- fetching a playlist ------------------------------------------------------


@pytest.mark.asyncio
async def test_a_playlist_is_fetched_with_its_headers_from_wherever_it_redirects() -> None:
    requests: list[httpx.Request] = []

    def cdn(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.host == "media.test":
            return httpx.Response(302, headers={"Location": "https://cdn.test/v/index.m3u8"})
        return httpx.Response(200, text="#EXTM3U\n")

    final, text = await fetch_playlist(
        "https://media.test/hls-1080", {"User-Agent": "UA"}, transport=httpx.MockTransport(cdn)
    )

    # Its relative URIs resolve against where it came from.
    assert (final, text) == ("https://cdn.test/v/index.m3u8", "#EXTM3U\n")
    assert [request.headers["User-Agent"] for request in requests] == ["UA", "UA"]


def _refuse_connection(request: httpx.Request) -> httpx.Response:
    raise httpx.ConnectError("connection refused", request=request)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("answer", "reason"),
    [
        (lambda request: httpx.Response(403), "status 403"),
        (lambda request: httpx.Response(503), "status 503"),
        (_refuse_connection, "connection refused"),
    ],
    ids=["403", "503", "network"],
)
async def test_a_playlist_that_will_not_load_is_a_bad_gateway_worth_retrying(
    answer: Callable[[httpx.Request], httpx.Response], reason: str
) -> None:
    with pytest.raises(Error) as caught:
        await fetch_playlist("https://media.test/hls-1080", {}, transport=httpx.MockTransport(answer))

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)
    assert caught.value.message == f"Couldn't read the stream's playlist: {reason}"


# --- a session stopped mid-request --------------------------------------------


@pytest.mark.asyncio
@pytest.mark.parametrize("url", ["https://files.test/movie.mkv", DAILYMOTION_PAGE], ids=["file", "hls"])
async def test_a_segment_asked_for_once_its_session_stopped_is_not_found(tmp_path: Path, url: str) -> None:
    # A request that got its session just before the player closed. Nothing
    # is encoded into the folder stop_session deleted.
    service, _, encoded = _site_service(tmp_path, FakeSiteClient(site_info("dailymotion")))
    session = await service.start_session(url)
    await service.stop_session(session.id)

    with pytest.raises(Error) as caught:
        await service.get_segment(session, 1)

    assert (caught.value.code, caught.value.message) == (Code.NOT_FOUND, "Stream session not found")
    assert encoded == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [_encode_failure, lambda: FileNotFoundError("segment_0.0.m3u8")],
    ids=["ffmpeg-failed", "cut-not-written"],
)
async def test_an_encode_that_fails_as_its_session_stops_is_not_found(
    tmp_path: Path, failure: Callable[[], Exception]
) -> None:
    # The player closes mid-encode, and the encode then fails in the deleted
    # folder. A 404 keeps ffmpeg's complaint out of the response and the log.
    services: list[StreamService] = []

    async def encoder(args: list[str]) -> None:
        await services[0].stop_session(Path(args[-1]).parent.name)
        raise failure()

    service, _ = _service(tmp_path, encoder=encoder, readahead_segments=0)
    services.append(service)
    session = await service.start_session("http://example.com/movie.mkv")

    with pytest.raises(Error) as caught:
        await service.get_segment(session, 0)

    assert (caught.value.code, caught.value.message) == (Code.NOT_FOUND, "Stream session not found")


@pytest.mark.asyncio
async def test_a_segment_finished_after_its_session_stopped_is_not_found(tmp_path: Path) -> None:
    # ffmpeg can finish into a file unlinked with its folder. Serving that path
    # would fail on a file that isn't there.
    services: list[StreamService] = []

    async def encoder(args: list[str]) -> None:
        Path(args[-1]).write_bytes(b"fake-ts-data")
        await services[0].stop_session(Path(args[-1]).parent.name)

    service, _ = _service(tmp_path, encoder=encoder, readahead_segments=0)
    services.append(service)
    session = await service.start_session("http://example.com/movie.mkv")

    with pytest.raises(Error) as caught:
        await service.get_segment(session, 0)

    assert caught.value.code == Code.NOT_FOUND


class FakeTaskFiles:
    """Stands in for ``DownloadService.resolve_media_file``: a task's file, by index."""

    def __init__(self, path: Path, index: int | None = None, fail: Error | None = None) -> None:
        self.path, self.index, self.fail = path, index, fail
        self.asked: list[tuple[uuid.UUID, int | None]] = []

    async def __call__(self, task_id: uuid.UUID, file_index: int | None) -> tuple[Path, str, int | None]:
        self.asked.append((task_id, file_index))
        if self.fail:
            raise self.fail
        return self.path, self.path.name, self.index if file_index is None else file_index


def _local_prober(seen: list[str]) -> Prober:
    async def prober(_ffprobe: str, source: str, **_: object) -> ProbeResult:
        seen.append(source)
        return ProbeResult(duration_seconds=30.0, has_video=True, container="mov,mp4,m4a,3gp,3g2,mj2",
                           video_codec="h264", audio_codec="aac")

    return prober


@pytest.mark.asyncio
async def test_media_info_probes_the_tasks_file_and_names_its_type(tmp_path: Path) -> None:
    """Whether the browser can play a download itself (#94)."""
    probed: list[str] = []
    files = FakeTaskFiles(tmp_path / "Movie.mp4", index=4)
    service, _ = _service(tmp_path, prober=_local_prober(probed), task_files=files)
    task_id = uuid.uuid4()

    info = await service.media_info(task_id, None)

    assert files.asked == [(task_id, None)]
    assert probed == [str(tmp_path / "Movie.mp4")]
    assert (info.file_index, info.filename, info.duration_seconds, info.has_video) == (4, "Movie.mp4", 30.0, True)
    assert info.media_type == 'video/mp4; codecs="avc1.640028, mp4a.40.2"'


@pytest.mark.asyncio
async def test_a_session_from_a_task_reads_its_file_from_disk(tmp_path: Path) -> None:
    probed: list[str] = []
    files = FakeTaskFiles(tmp_path / "Movie.mkv")
    service, _ = _service(tmp_path, prober=_local_prober(probed), task_files=files)
    task_id = uuid.uuid4()

    session = await service.start_task_session(task_id, 2)

    assert files.asked == [(task_id, 2)]
    assert [source.url for source in session.inputs] == [str(tmp_path / "Movie.mkv")]
    assert session.inputs[0].headers == {}
    assert (session.duration_seconds, session.has_video, session.info_hash) == (30.0, True, None)
    assert service.get_session(session.id) is session


@pytest.mark.asyncio
async def test_a_task_the_resolver_refuses_starts_nothing(tmp_path: Path) -> None:
    files = FakeTaskFiles(tmp_path / "x", fail=Error.conflict(message="Task is downloading, not complete"))
    service, _ = _service(tmp_path, task_files=files)

    with pytest.raises(Error) as caught:
        await service.start_task_session(uuid.uuid4(), None)

    assert caught.value.code == Code.CONFLICT
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_playing_tasks_needs_a_resolver(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)

    with pytest.raises(Error) as caught:
        await service.media_info(uuid.uuid4(), None)

    assert caught.value.code == Code.SERVICE_UNAVAILABLE


@pytest.mark.asyncio
async def test_a_torrent_still_downloading_plays_from_its_torrent(tmp_path: Path) -> None:
    """#95: the task's own torrent, streamed by rqbit, and never added again."""
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    disk = FakeTaskFiles(tmp_path / "unused.mkv")
    asked: list[tuple[uuid.UUID, int | None]] = []

    async def torrent_play(task_id: uuid.UUID, file_index: int | None) -> TorrentPlay | None:
        asked.append((task_id, file_index))
        return TorrentPlay("deadbeef", 1)

    service, _ = _service(
        tmp_path,
        torrent_client=torrent_client,
        task_repo=FakeTaskRepo(),
        torrent_dir=tmp_path / "torrent",
        torrent_api_url="http://torrent-anydm-api:3030",
        task_files=disk,
        torrent_play=torrent_play,
    )
    task_id = uuid.uuid4()

    session = await service.start_task_session(task_id, None)
    await asyncio.gather(*session.background_tasks)

    assert asked == [(task_id, None)]
    assert torrent_client.added == []
    assert disk.asked == []
    assert session.inputs[0].url == "http://torrent-anydm-api:3030/torrents/deadbeef/stream/1"
    assert session.info_hash == "deadbeef"
    assert session.status == "ready"


@pytest.mark.asyncio
async def test_a_finished_torrent_still_plays_from_disk(tmp_path: Path) -> None:
    async def torrent_play(_task_id: uuid.UUID, _file_index: int | None) -> TorrentPlay | None:
        return None

    disk = FakeTaskFiles(tmp_path / "Movie.mkv", index=1)
    service, _ = _service(tmp_path, task_files=disk, torrent_play=torrent_play)

    session = await service.start_task_session(uuid.uuid4(), None)

    assert [source.url for source in session.inputs] == [str(tmp_path / "Movie.mkv")]
    assert session.info_hash is None


# --- audio tracks (#99) ---------------------------------------------------------

DUB_THEN_ORIGINAL = (
    AudioTrack(0, language="spa", channels=6, codec="ac3", default=True),
    AudioTrack(1, language="eng", channels=2, codec="aac"),
)


async def _two_tracks(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
    return ProbeResult(duration_seconds=20.0, has_video=True, audio_tracks=DUB_THEN_ORIGINAL)


def _maps(args: list[str]) -> list[str]:
    return [args[i + 1] for i, arg in enumerate(args) if arg == "-map"]


@pytest.mark.asyncio
async def test_a_file_opens_with_the_preferred_language(tmp_path: Path) -> None:
    service, encoded = _service(tmp_path, prober=_two_tracks, readahead_segments=0)

    session = await service.start_session("http://example.com/movie.mkv", audio_language="en")
    await service.get_segment(session, 0)

    assert session.audio_tracks == list(DUB_THEN_ORIGINAL)
    assert session.audio_track == 1
    assert _maps(encoded[-1]) == ["0:v:0", "0:a:1"]


@pytest.mark.asyncio
async def test_a_file_opens_with_its_marked_track_without_a_preference(tmp_path: Path) -> None:
    service, encoded = _service(tmp_path, prober=_two_tracks, readahead_segments=0)

    session = await service.start_session("http://example.com/movie.mkv", audio_language="ja")
    await service.get_segment(session, 0)

    assert session.audio_track == 0
    assert _maps(encoded[-1]) == ["0:v:0", "0:a:0"]


@pytest.mark.asyncio
async def test_a_named_track_beats_the_preference(tmp_path: Path) -> None:
    """What native playback asks for when it hands over to a session."""
    service, _ = _service(tmp_path, prober=_two_tracks)

    session = await service.start_session("http://example.com/movie.mkv", audio_language="es", audio_track=1)

    assert session.audio_track == 1


@pytest.mark.asyncio
async def test_a_switch_is_a_new_session_on_the_same_source(tmp_path: Path) -> None:
    probes: list[str] = []

    async def counting(_ffprobe: str, source: str, **_: object) -> ProbeResult:
        probes.append(source)
        return await _two_tracks(_ffprobe, source)

    service, encoded = _service(tmp_path, prober=counting, readahead_segments=0)
    old = await service.start_session("http://example.com/movie.mkv")

    new = await service.switch_audio(old, 1)
    await service.get_segment(new, 2)

    assert new.id != old.id
    assert (new.inputs, new.duration_seconds, new.audio_track) == (old.inputs, old.duration_seconds, 1)
    assert _maps(encoded[-1]) == ["0:v:0", "0:a:1"]
    # Nothing probed again, and the old one still plays until the player stops it.
    assert probes == ["http://example.com/movie.mkv"]
    assert service.get_session(old.id) is old
    assert old.audio_track == 0


@pytest.mark.asyncio
async def test_a_switch_refuses_a_track_the_source_lacks(tmp_path: Path) -> None:
    service, _ = _service(tmp_path, prober=_two_tracks)
    session = await service.start_session("http://example.com/movie.mkv")

    with pytest.raises(Error) as caught:
        await service.switch_audio(session, 2)
    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


@pytest.mark.asyncio
async def test_a_torrent_picks_its_track_once_probed_and_switches_on_the_same_torrent(tmp_path: Path) -> None:
    torrent_client = FakeTorrentClient(details=TORRENT_DETAILS)
    service, encoded = _torrent_service(
        tmp_path, torrent_client=torrent_client, task_repo=FakeTaskRepo(), prober=_two_tracks
    )

    old = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef", audio_language="eng")
    with pytest.raises(Error) as caught:
        await service.switch_audio(old, 0)
    assert caught.value.code == Code.CONFLICT
    await asyncio.gather(*old.background_tasks)
    assert (old.audio_tracks, old.audio_track) == (list(DUB_THEN_ORIGINAL), 1)

    new = await service.switch_audio(old, 0)
    await service.get_segment(new, 0)

    assert (new.info_hash, new.inputs) == (old.info_hash, old.inputs)
    assert new.progress_task is not None
    assert _maps(encoded[-1]) == ["0:v:0", "0:a:0"]
    assert len(torrent_client.added) == 1

    # The old one goes first, while the new one still needs the torrent.
    await service.stop_session(old.id)
    assert torrent_client.deleted == []
    await service.stop_session(new.id)
    assert torrent_client.deleted == ["deadbeef"]
    assert new.progress_task.cancelled()


def _dubbed(*, hls: bool = False) -> SiteInfo:
    protocol = "m3u8_native" if hls else "https"

    def audio(format_id: str, language: str, preference: int) -> dict[str, object]:
        return {
            "format_id": format_id, "protocol": protocol, "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2",
            "tbr": 128, "language": language, "language_preference": preference, "audio_channels": 2,
        }

    formats = [
        {"format_id": "137", "protocol": protocol, "ext": "mp4", "vcodec": "avc1.640028", "acodec": "none",
         "height": 1080, "tbr": 4000},
        audio("140-en", "en", 10),
        audio("140-es", "es-US", -1),
        audio("140-de", "de", -1),
    ]
    return site_info("youtube", formats=[Format.from_ytdlp(f) for f in formats])


@pytest.mark.asyncio
async def test_a_page_s_dubs_are_its_tracks(tmp_path: Path) -> None:
    client = FakeSiteClient(_dubbed())
    service, _, encoded = _site_service(tmp_path, client)

    session = await service.start_session(YOUTUBE_PAGE, audio_language="spa")
    await service.get_segment(session, 0)

    assert [(t.index, t.language, t.default) for t in session.audio_tracks] == [
        (0, "en", True), (1, "es-US", False), (2, "de", False),
    ]
    assert session.audio_track == 1
    assert session.inputs[-1] == MediaInput(media_url("youtube", "140-es"), HEADERS)
    # A site's audio is its own input: nothing to map from one file.
    assert _maps(encoded[-1]) == ["0:v:0", "1:a:0"]


@pytest.mark.asyncio
async def test_a_page_switch_swaps_its_audio_input_without_asking_the_site(tmp_path: Path) -> None:
    client = FakeSiteClient(_dubbed())
    service, _, _ = _site_service(tmp_path, client)
    old = await service.start_session(YOUTUBE_PAGE)

    new = await service.switch_audio(old, 2)

    assert new.inputs == [old.inputs[0], MediaInput(media_url("youtube", "140-de"), HEADERS)]
    assert new.origin == SiteOrigin(_dubbed().webpage_url, ("137", "140-de"))
    assert new.audio_track == 2
    assert client.opened == [YOUTUBE_PAGE]
    assert client.resolved == []


@pytest.mark.asyncio
async def test_an_hls_page_switch_reads_only_the_new_audio_playlist(tmp_path: Path) -> None:
    playlists = FakePlaylists()
    service, _, _ = _site_service(tmp_path, FakeSiteClient(_dubbed(hls=True)), playlist_fetcher=playlists)
    old = await service.start_session(YOUTUBE_PAGE)
    playlists.fetched.clear()

    new = await service.switch_audio(old, 1)

    assert [url for url, _ in playlists.fetched] == [media_url("youtube", "140-es")]
    assert new.playlists[0] is old.playlists[0]
    assert len(new.playlists) == 2



# --- embedded subtitles (#100) ------------------------------------------------------

SUBTITLED = (
    SubtitleTrack(0, language="eng", codec="subrip"),
    SubtitleTrack(1, language="eng", codec="ass"),
    SubtitleTrack(2, language="eng", codec="hdmv_pgs_subtitle", forced=True),
)


async def _subtitled(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
    return ProbeResult(duration_seconds=20.0, has_video=True, subtitle_tracks=SUBTITLED)


def _cut_outputs(args: list[str]) -> list[str]:
    return [args[i + 1] for i, arg in enumerate(args) if arg == "-map"]


@pytest.mark.asyncio
async def test_a_file_s_session_lists_its_subtitle_tracks(tmp_path: Path) -> None:
    service, _ = _service(tmp_path, prober=_subtitled)

    session = await service.start_session("http://example.com/movie.mkv")

    assert session.subtitle_tracks == list(SUBTITLED)


@pytest.mark.asyncio
async def test_a_segment_s_cues_are_cut_once_for_every_text_track(tmp_path: Path) -> None:
    service, encoded = _service(tmp_path, prober=_subtitled, readahead_segments=0)
    session = await service.start_session("http://example.com/movie.mkv")

    first, second, again = await asyncio.gather(
        service.get_subtitle_segment(session, 0, 1),
        service.get_subtitle_segment(session, 1, 1),
        service.get_subtitle_segment(session, 0, 1),
    )

    assert len(encoded) == 1
    # The two text tracks, never the picture one.
    assert _cut_outputs(encoded[0]) == ["0:s:0", "0:s:1"]
    assert encoded[0][encoded[0].index("-ss") + 1] == "6"
    assert (first, second, again) == (session.cue_path(1, 0), session.cue_path(1, 1), session.cue_path(1, 0))
    # No video encoded for it, and the next segment is a cut of its own.
    await service.get_subtitle_segment(session, 0, 2)
    assert len(encoded) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(("track", "index"), [(2, 0), (5, 0), (0, 4), (0, -1)])
async def test_a_track_or_segment_that_can_t_be_shown_is_not_found(tmp_path: Path, track: int, index: int) -> None:
    service, encoded = _service(tmp_path, prober=_subtitled)
    session = await service.start_session("http://example.com/movie.mkv")

    with pytest.raises(Error) as caught:
        await service.get_subtitle_segment(session, track, index)
    assert caught.value.code == Code.NOT_FOUND
    assert encoded == []


@pytest.mark.asyncio
async def test_an_audio_switch_keeps_the_subtitle_tracks(tmp_path: Path) -> None:
    async def both(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(
            duration_seconds=20.0, has_video=True, audio_tracks=DUB_THEN_ORIGINAL, subtitle_tracks=SUBTITLED
        )

    service, _ = _service(tmp_path, prober=both)
    old = await service.start_session("http://example.com/movie.mkv")

    assert (await service.switch_audio(old, 1)).subtitle_tracks == list(SUBTITLED)


@pytest.mark.asyncio
async def test_a_torrent_s_subtitle_tracks_arrive_when_it_s_probed(tmp_path: Path) -> None:
    service, _ = _torrent_service(
        tmp_path, torrent_client=FakeTorrentClient(details=TORRENT_DETAILS), task_repo=FakeTaskRepo(),
        prober=_subtitled,
    )
    from src.lib.event import EventHub

    hub = EventHub()
    published: list[dict] = []
    hub.publish = lambda _event, data: published.append(data)  # ty: ignore[invalid-assignment]
    service._event_hub = hub

    session = await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")
    assert session.subtitle_tracks == []
    await asyncio.gather(*session.background_tasks)

    # The embedded tracks, then the film's subtitle file after them (#101).
    assert session.subtitle_tracks[:3] == list(SUBTITLED)
    assert session.subtitle_tracks[3] == SubtitleTrack(
        3, language="en", title="Movie.en.srt", codec="subrip", external=True
    )
    ready = next(data for data in published if data["status"] == "ready")
    assert [(t["index"], t["text"], t["external"]) for t in ready["subtitle_tracks"]] == [
        (0, True, False), (1, True, False), (2, False, False), (3, True, True),
    ]
    await service.stop_session(session.id)


@pytest.mark.asyncio
async def test_a_download_s_subtitle_track_is_extracted_whole_once(tmp_path: Path) -> None:
    movie = tmp_path / "movie.mkv"
    movie.write_bytes(b"mkv")
    files = FakeTaskFiles(movie)
    service, encoded = _service(tmp_path / "stream", prober=_subtitled, task_files=files)
    task = uuid.uuid4()

    first = await service.subtitle_file(task, None, 1)
    second = await service.subtitle_file(task, None, 1)

    assert first == second
    assert first.read_bytes() == b"fake-ts-data"
    assert len(encoded) == 1
    assert encoded[0][encoded[0].index("-map") + 1] == "0:s:1"
    assert "-copyts" in encoded[0]
    # Nothing half-written left beside it.
    assert [p.name for p in first.parent.iterdir()] == [first.name]


@pytest.mark.asyncio
async def test_a_download_s_picture_subtitles_are_not_found(tmp_path: Path) -> None:
    movie = tmp_path / "movie.mkv"
    movie.write_bytes(b"mkv")
    service, encoded = _service(tmp_path / "stream", prober=_subtitled, task_files=FakeTaskFiles(movie))

    with pytest.raises(Error) as caught:
        await service.subtitle_file(uuid.uuid4(), None, 2)
    assert caught.value.code == Code.NOT_FOUND
    assert encoded == []



# --- subtitle files beside the video (#101) -------------------------------------------

class FakeSidecars:
    """Stands in for ``DownloadService.subtitle_files``."""

    def __init__(self, found: list) -> None:
        self.found = found
        self.asked: list[tuple[uuid.UUID, int | None]] = []

    async def __call__(self, task_id: uuid.UUID, file_index: int | None) -> list:
        self.asked.append((task_id, file_index))
        return self.found


class RecordingEncoder:
    """Writes each output, and keeps what the input file said when it was converted."""

    def __init__(self) -> None:
        self.calls: list[list[str]] = []
        self.read: list[str] = []

    async def __call__(self, args: list[str]) -> None:
        self.calls.append(args)
        source = Path(args[args.index("-i") + 1])
        if source.suffix in (".srt", ".ass", ".vtt"):
            self.read.append(source.read_text(encoding="utf-8"))
        Path(args[-1]).write_text("WEBVTT\n")


SRT_1252 = "1\n00:00:01,000 --> 00:00:02,000\nCaf\u00e9, se\u00f1or\n".encode("cp1252")


@pytest.mark.asyncio
async def test_a_download_s_subtitle_files_follow_its_embedded_tracks(tmp_path: Path) -> None:
    movie = tmp_path / "Movie.mkv"
    movie.write_bytes(b"mkv")
    srt = tmp_path / "Movie.fr.srt"
    srt.write_bytes(SRT_1252)
    sidecars = FakeSidecars([(Sidecar("Movie.fr.srt", "fr", "Movie.fr.srt"), srt)])
    encoder = RecordingEncoder()
    service, _ = _service(
        tmp_path / "stream", prober=_subtitled, task_files=FakeTaskFiles(movie), task_sidecars=sidecars,
        encoder=encoder,
    )
    task = uuid.uuid4()

    session = await service.start_task_session(task, None)
    info = await service.media_info(task, None)

    external = SubtitleTrack(3, language="fr", title="Movie.fr.srt", codec="subrip", external=True)
    assert session.subtitle_tracks == [*SUBTITLED, external]
    assert info.subtitle_tracks == (*SUBTITLED, external)

    first, again = await asyncio.gather(service.get_subtitle_file(session, 3), service.get_subtitle_file(session, 3))

    assert first == again == session.subtitle_file_path(3)
    # Read once, as Windows-1252, and handed to ffmpeg as UTF-8.
    assert encoder.read == ["1\n00:00:01,000 --> 00:00:02,000\nCaf\u00e9, se\u00f1or\n"]
    assert len(encoder.calls) == 1
    # Nothing left beside it but the result.
    assert sorted(p.name for p in session.session_dir.iterdir()) == ["subtitles_file_3.vtt"]


@pytest.mark.asyncio
async def test_a_subtitle_file_is_never_cut_by_the_segment(tmp_path: Path) -> None:
    movie = tmp_path / "Movie.mkv"
    movie.write_bytes(b"mkv")
    sidecars = FakeSidecars([(Sidecar("Movie.en.srt", "en", "Movie.en.srt"), tmp_path / "Movie.en.srt")])
    service, encoded = _service(
        tmp_path / "stream", prober=_subtitled, task_files=FakeTaskFiles(movie), task_sidecars=sidecars
    )
    session = await service.start_task_session(uuid.uuid4(), None)

    with pytest.raises(Error) as caught:
        await service.get_subtitle_segment(session, 3, 0)
    assert caught.value.code == Code.NOT_FOUND
    # And an embedded track has no whole-file route in a session.
    with pytest.raises(Error):
        await service.get_subtitle_file(session, 0)
    assert encoded == []


@pytest.mark.asyncio
async def test_a_downloading_torrent_s_subtitle_file_is_read_through_rqbit(tmp_path: Path) -> None:
    fetched: list[str] = []

    async def fetch(url: str) -> bytes:
        fetched.append(url)
        return b"1\n00:00:01,000 --> 00:00:02,000\nhi\n"

    async def playing(_task: uuid.UUID, _index: int | None) -> TorrentPlay:
        return TorrentPlay(info_hash="deadbeef", file_index=1)

    sidecars = FakeSidecars([(Sidecar("Subs/2_English.srt", "en", "2_English.srt"), TorrentFile("deadbeef", 4))])
    encoder = RecordingEncoder()
    service, _ = _torrent_service(
        tmp_path, torrent_client=FakeTorrentClient(details=TORRENT_DETAILS), task_repo=FakeTaskRepo(),
        prober=_subtitled,
    )
    service._torrent_play = playing
    service._task_sidecars = sidecars
    service._fetch_bytes = fetch
    service._encoder = encoder
    task = uuid.uuid4()

    session = await service.start_task_session(task, None)
    await asyncio.gather(*session.background_tasks)
    await service.get_subtitle_file(session, 3)

    assert sidecars.asked == [(task, 1)]
    assert session.subtitle_tracks[3].external
    assert fetched == ["http://torrent-anydm-api:3030/torrents/deadbeef/stream/4"]
    assert encoder.read == ["1\n00:00:01,000 --> 00:00:02,000\nhi\n"]
    await service.stop_session(session.id)


@pytest.mark.asyncio
async def test_an_audio_switch_keeps_the_subtitle_files(tmp_path: Path) -> None:
    movie = tmp_path / "Movie.mkv"
    movie.write_bytes(b"mkv")

    async def both(_ffprobe: str, _source: str, **_: object) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True, audio_tracks=DUB_THEN_ORIGINAL)

    srt = tmp_path / "Movie.en.srt"
    srt.write_bytes(b"1\n00:00:01,000 --> 00:00:02,000\nhi\n")
    sidecars = FakeSidecars([(Sidecar("Movie.en.srt", "en", "Movie.en.srt"), srt)])
    service, _ = _service(
        tmp_path / "stream", prober=both, task_files=FakeTaskFiles(movie), task_sidecars=sidecars,
        encoder=RecordingEncoder(),
    )
    old = await service.start_task_session(uuid.uuid4(), None)

    new = await service.switch_audio(old, 1)

    assert new.subtitle_files == old.subtitle_files
    assert (await service.get_subtitle_file(new, 0)).exists()


@pytest.mark.asyncio
async def test_a_download_played_as_it_is_gets_its_subtitle_file_converted_once(tmp_path: Path) -> None:
    movie = tmp_path / "Movie.mkv"
    movie.write_bytes(b"mkv")
    srt = tmp_path / "Movie.en.srt"
    srt.write_bytes(b"1\n00:00:01,000 --> 00:00:02,000\nhi\n")
    encoder = RecordingEncoder()
    service, _ = _service(
        tmp_path / "stream", prober=_subtitled, task_files=FakeTaskFiles(movie), encoder=encoder,
        task_sidecars=FakeSidecars([(Sidecar("Movie.en.srt", "en", "Movie.en.srt"), srt)]),
    )
    task = uuid.uuid4()

    first = await service.subtitle_file(task, None, 3)
    again = await service.subtitle_file(task, None, 3)

    assert first == again
    assert encoder.read == ["1\n00:00:01,000 --> 00:00:02,000\nhi\n"]
    # Converted, not extracted: its own file is the input, its only track.
    assert encoder.calls[0][encoder.calls[0].index("-map") + 1] == "0:s:0"
    with pytest.raises(Error):
        await service.subtitle_file(task, None, 4)


# --- a site's own subtitles (#102) ---------------------------------------------------

def _captioned(url_tag: str = "a") -> SiteInfo:
    return site_info(
        "youtube",
        subtitles=[
            SiteSubtitle("en", "English", False, "vtt", f"https://yt.test/en-{url_tag}.vtt", {"User-Agent": "UA"}),
            SiteSubtitle("en", "English (auto-generated)", True, "vtt", f"https://yt.test/auto-{url_tag}.vtt"),
        ],
    )


class FakeSubtitles:
    """A site's subtitle server, refusing the URLs it's told have expired."""

    def __init__(self, expired: set[str] | None = None) -> None:
        self.expired = expired or set()
        self.fetched: list[tuple[str, dict[str, str]]] = []

    async def __call__(self, url: str, headers: Mapping[str, str]) -> bytes:
        self.fetched.append((url, dict(headers)))
        if url in self.expired:
            raise Error.create(code=Code.FORBIDDEN, message="403", error_type=ErrorType.EXTERNAL_API_ERROR)
        return b"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhello\n"


@pytest.mark.asyncio
async def test_a_page_s_subtitles_and_captions_are_its_tracks(tmp_path: Path) -> None:
    subtitles = FakeSubtitles()
    service, _, _ = _site_service(tmp_path, FakeSiteClient(_captioned()), subtitle_fetcher=subtitles)
    service._encoder = RecordingEncoder()

    session = await service.start_session(YOUTUBE_PAGE)
    await service.get_subtitle_file(session, 1)

    assert [(t.index, t.language, t.title, t.external) for t in session.subtitle_tracks] == [
        (0, "en", "English", True),
        (1, "en", "English (auto-generated)", True),
    ]
    assert subtitles.fetched == [("https://yt.test/auto-a.vtt", {})]


@pytest.mark.asyncio
async def test_an_expired_subtitle_url_asks_the_site_again_once(tmp_path: Path) -> None:
    client = FakeSiteClient(_captioned("a"))
    subtitles = FakeSubtitles(expired={"https://yt.test/en-a.vtt"})
    service, _, _ = _site_service(tmp_path, client, subtitle_fetcher=subtitles)
    service._encoder = RecordingEncoder()
    session = await service.start_session(YOUTUBE_PAGE)
    client.info = _captioned("b")

    path = await service.get_subtitle_file(session, 0)

    assert path.exists()
    assert [url for url, _ in subtitles.fetched] == ["https://yt.test/en-a.vtt", "https://yt.test/en-b.vtt"]
    # With the site's headers both times.
    assert all(headers == {"User-Agent": "UA"} for _, headers in subtitles.fetched)
    assert client.opened == [YOUTUBE_PAGE, YOUTUBE_PAGE]


@pytest.mark.asyncio
async def test_a_subtitle_url_that_stays_refused_fails(tmp_path: Path) -> None:
    subtitles = FakeSubtitles(expired={"https://yt.test/en-a.vtt"})
    service, _, _ = _site_service(tmp_path, FakeSiteClient(_captioned("a")), subtitle_fetcher=subtitles)
    service._encoder = RecordingEncoder()
    session = await service.start_session(YOUTUBE_PAGE)

    with pytest.raises(Error) as caught:
        await service.get_subtitle_file(session, 0)
    assert caught.value.code == Code.FORBIDDEN
    assert len(subtitles.fetched) == 2
