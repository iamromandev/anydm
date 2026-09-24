import asyncio
from pathlib import Path

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.media.ffprobe import ProbeResult
from src.lib.media.source import MediaInput
from src.lib.site import error as site_error
from src.lib.site.format import playback_plan
from src.lib.torrent.protocol import FileInfo, TorrentDetails, TorrentProgress
from src.lib.torrent.source import TorrentSource
from src.service.stream.session import SegmentState, SiteOrigin, StreamSessionStore
from src.service.stream.stream_service import Prober, StreamService

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
    assert torrent_client.added == [
        {"only_files": [1], "output_folder": str(tmp_path / "torrent")}
    ]


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
FORBIDDEN ="ffmpeg exited with 8: [https @ 0x1] HTTP error 403 Forbidden"


def _site_service(
    tmp_path: Path, client: FakeSiteClient, *, encoder: object = None, **overrides: object
) -> tuple[StreamService, list[tuple[str, object]], list[list[str]]]:
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
    plan = playback_plan(site_info(site).formats)
    return tuple(part.id for part in (plan.video, plan.audio) if part is not None)


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
