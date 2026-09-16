import asyncio
from pathlib import Path

import pytest
from src.core.error import Error
from src.lib.media.ffprobe import ProbeResult
from src.lib.torrent.protocol import FileInfo, TorrentDetails
from src.lib.torrent.source import TorrentSource
from src.service.stream.session import SegmentState, StreamSessionStore
from src.service.stream.stream_service import Prober, StreamService


def _service(tmp_path: Path, **overrides: object) -> tuple[StreamService, list[list[str]]]:
    encoded_calls: list[list[str]] = []

    async def fake_prober(_ffprobe: str, _source: str) -> ProbeResult:
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

    async def slow_prober(_ffprobe: str, _source: str) -> ProbeResult:
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
    async def fake_prober(_ffprobe: str, _source: str) -> ProbeResult:
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

    async def resolve(self, source: TorrentSource) -> TorrentDetails:
        if self.fail:
            raise self.fail
        return self.details

    async def add(self, source: TorrentSource, *, only_files, output_folder: str) -> TorrentDetails:
        self.added.append({"only_files": list(only_files), "output_folder": output_folder})
        return self.details

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

    async def default_prober(_ffprobe: str, _source: str) -> ProbeResult:
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

    assert session.info_hash == "deadbeef"
    assert session.duration_seconds == 20.0  # from the fake prober
    assert torrent_client.added == [
        {"only_files": [1], "output_folder": str(tmp_path / "torrent")}
    ]


@pytest.mark.asyncio
async def test_start_torrent_session_builds_the_rqbit_stream_url(tmp_path: Path) -> None:
    calls: list[str] = []

    async def recording_prober(_ffprobe: str, source: str) -> ProbeResult:
        calls.append(source)
        return ProbeResult(duration_seconds=20.0, has_video=True)

    service, _ = _torrent_service(
        tmp_path,
        torrent_client=FakeTorrentClient(details=TORRENT_DETAILS),
        task_repo=FakeTaskRepo(),
        prober=recording_prober,
    )

    await service.start_torrent_session("magnet:?xt=urn:btih:deadbeef")

    assert calls == ["http://torrent-anydm-api:3030/torrents/deadbeef/stream/1"]


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
