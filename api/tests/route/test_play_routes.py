"""Playing a finished download: the routes (#94).

Driven through the real app with a fake ``StreamService``, as ``test_auth`` is.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.schema.download import PositionSchema
from src.lib.media.audio import AudioTrack
from src.lib.media.subtitle import SubtitleTrack
from src.main import app
from src.service import get_download_service, get_stream_service
from src.service.stream.stream_service import MediaInfo

TASK = uuid.uuid4()

TRACKS = [AudioTrack(0, language="spa", channels=6, codec="ac3", default=True), AudioTrack(1, language="eng")]
SUBTITLES = [
    SubtitleTrack(0, language="eng", codec="subrip"),
    SubtitleTrack(1, language="eng", codec="hdmv_pgs_subtitle", forced=True),
    SubtitleTrack(2, language="en", title="Movie.en.srt", codec="subrip", external=True),
]
VTT = "WEBVTT\n\n00:00:05.000 --> 00:00:07.000\nHello\n"


def _session(session_id: str, status: str, duration: float, track: int | None = None) -> Any:
    return type("S", (), {
        "id": session_id, "status": status, "duration_seconds": duration, "has_video": True,
        "audio_tracks": TRACKS if track is not None else [], "audio_track": track,
        "subtitle_tracks": SUBTITLES if track is not None else [], "segment_seconds": 6,
    })()


class _FakeStream:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []
        #: What the subtitle routes serve; the fixture writes it.
        self.vtt = Path()

    async def media_info(self, task_id: uuid.UUID, file_index: int | None) -> MediaInfo:
        self.calls.append(("media_info", (task_id, file_index)))
        return MediaInfo(
            file_index=file_index if file_index is not None else (3 if task_id != TASK else None),
            filename="Movie.mp4",
            duration_seconds=30.0,
            has_video=True,
            media_type='video/mp4; codecs="avc1.640028, mp4a.40.2"',
            audio_tracks=tuple(TRACKS) if task_id != TASK else (),
        )

    async def start_task_session(self, task_id: uuid.UUID, file_index: int | None, **audio: Any) -> Any:
        self.calls.append(("start_task_session", (task_id, file_index)))
        self.audio = audio
        return _session("s1", "ready", 30.0)

    async def start_torrent_session(self, raw: str, file_index: int | None = None, **audio: Any) -> Any:
        self.calls.append(("start_torrent_session", (raw, file_index)))
        self.audio = audio
        return _session("s3", "connecting", 0.0)

    async def start_session(self, url: str, **audio: Any) -> Any:
        self.calls.append(("start_session", url))
        self.audio = audio
        return _session("s2", "ready", 1.0, track=0)

    def get_session(self, session_id: str) -> Any:
        return _session(session_id, "ready", 30.0, track=0)

    async def get_subtitle_segment(self, session: Any, track: int, index: int) -> Any:
        self.calls.append(("get_subtitle_segment", (session.id, track, index)))
        return self.vtt

    async def get_subtitle_file(self, session: Any, track: int) -> Any:
        self.calls.append(("get_subtitle_file", (session.id, track)))
        return self.vtt

    async def subtitle_file(self, task_id: uuid.UUID, file_index: int | None, track: int) -> Any:
        self.calls.append(("subtitle_file", (task_id, file_index, track)))
        return self.vtt

    async def switch_audio(self, session: Any, track: int) -> Any:
        self.calls.append(("switch_audio", (session.id, track)))
        return _session("s9", "ready", 30.0, track=track)


@pytest.fixture
def stream(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> Iterator[_FakeStream]:
    fake = _FakeStream()
    fake.vtt = tmp_path / "cues.vtt"
    fake.vtt.write_text(VTT)
    monkeypatch.setattr(get_settings(), "api_key", None)
    app.dependency_overrides[get_stream_service] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as http:
        yield http


@pytest.mark.asyncio
async def test_media_names_the_file_and_where_to_fetch_it(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.get(f"/download/{TASK}/media")

    assert response.status_code == 200
    # A download's one file has no index, and the envelope leaves out what is None.
    assert response.json()["data"] == {
        "filename": "Movie.mp4",
        "duration_seconds": 30.0,
        "has_video": True,
        "media_type": 'video/mp4; codecs="avc1.640028, mp4a.40.2"',
        "file_url": f"/download/{TASK}/file",
        "audio_tracks": [],
        "subtitle_tracks": [],
    }
    assert stream.calls == [("media_info", (TASK, None))]


@pytest.mark.asyncio
async def test_a_torrents_media_points_at_that_file(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    other = uuid.uuid4()
    response = await client.get(f"/download/{other}/media", params={"file_index": 5})

    assert response.json()["data"]["file_url"] == f"/download/{other}/file/5"
    # Every track, so the player knows a file needs a session for any but the default (#99).
    assert response.json()["data"]["audio_tracks"] == [
        {"index": 0, "language": "spa", "channels": 6, "codec": "ac3", "default": True},
        {"index": 1, "language": "eng", "default": False},
    ]
    assert stream.calls == [("media_info", (other, 5))]


@pytest.mark.asyncio
async def test_a_session_starts_from_a_task(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.post("/stream/start", json={"task_id": str(TASK), "file_index": 2})

    assert response.status_code == 201
    assert response.json()["data"]["session_id"] == "s1"
    assert stream.calls == [("start_task_session", (TASK, 2))]
    assert stream.audio == {"audio_language": None, "audio_track": None}


@pytest.mark.asyncio
async def test_a_start_passes_on_the_audio_it_asks_for(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.post(
        "/stream/start", json={"url": "https://example.com/a.mkv", "audio_language": "en-US", "audio_track": 1}
    )

    assert response.status_code == 201
    assert stream.audio == {"audio_language": "en-US", "audio_track": 1}
    data = response.json()["data"]
    assert data["audio_track"] == 0
    assert [track["language"] for track in data["audio_tracks"]] == ["spa", "eng"]


@pytest.mark.asyncio
async def test_a_connecting_session_names_no_tracks_yet(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.post("/stream/start", json={"torrent": "magnet:?xt=urn:btih:abc"})

    assert "audio_tracks" not in response.json()["data"]


@pytest.mark.asyncio
async def test_a_switch_answers_with_the_new_session(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.post("/stream/s1/audio", json={"track": 1})

    assert response.status_code == 201
    data = response.json()["data"]
    assert (data["session_id"], data["playlist_url"], data["audio_track"]) == ("s9", "/stream/s9/playlist.m3u8", 1)
    assert stream.calls == [("switch_audio", ("s1", 1))]


@pytest.mark.asyncio
async def test_a_switch_needs_a_track(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    assert (await client.post("/stream/s1/audio", json={})).status_code in (400, 422)
    assert (await client.post("/stream/s1/audio", json={"track": -1})).status_code in (400, 422)
    assert stream.calls == []


@pytest.mark.asyncio
async def test_a_torrent_stream_can_name_its_file(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    """#98: episode 2 from the torrent dialog, not whichever is biggest."""
    response = await client.post("/stream/start", json={"torrent": "magnet:?xt=urn:btih:abc", "file_index": 1})

    assert response.status_code == 201
    assert stream.calls == [("start_torrent_session", ("magnet:?xt=urn:btih:abc", 1))]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "body",
    [
        {"task_id": str(TASK), "url": "https://example.com/a.mp4"},
        {"url": "https://example.com/a.mp4", "torrent": "magnet:?xt=urn:btih:abc"},
        {"url": "https://example.com/a.mp4", "file_index": 1},
        {"file_index": 1},
        {},
    ],
)
async def test_a_start_names_exactly_one_source(
    client: httpx.AsyncClient, stream: _FakeStream, body: dict[str, Any]
) -> None:
    response = await client.post("/stream/start", json=body)

    assert response.status_code in (400, 422)
    assert stream.calls == []


class _FakeDownloads:
    def __init__(self) -> None:
        self.saved: list[tuple[uuid.UUID, int | None, float, float]] = []

    async def save_position(
        self, task_id: uuid.UUID, file_index: int | None, *, position_seconds: float, duration_seconds: float
    ) -> PositionSchema:
        self.saved.append((task_id, file_index, position_seconds, duration_seconds))
        return PositionSchema(file_index=file_index or 0, position_seconds=position_seconds,
                              duration_seconds=duration_seconds)


@pytest.mark.asyncio
async def test_a_position_is_saved_through_the_route(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """#96: the player's regular save."""
    fake = _FakeDownloads()
    monkeypatch.setattr(get_settings(), "api_key", None)
    app.dependency_overrides[get_download_service] = lambda: fake
    try:
        response = await client.put(
            f"/download/{TASK}/position", json={"file_index": 2, "position_seconds": 61.5, "duration_seconds": 1300}
        )
        refused = await client.put(f"/download/{TASK}/position", json={"position_seconds": -1, "duration_seconds": 1})
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["data"]["position_seconds"] == 61.5
    assert fake.saved == [(TASK, 2, 61.5, 1300.0)]
    assert refused.status_code == 422


@pytest.mark.asyncio
async def test_a_session_lists_its_subtitles_and_says_which_can_be_shown(
    client: httpx.AsyncClient, stream: _FakeStream
) -> None:
    """#100: a picture track is listed, marked as one the player can't show."""
    data = (await client.post("/stream/start", json={"url": "https://example.com/a.mkv"})).json()["data"]

    assert data["segment_seconds"] == 6
    assert data["subtitle_tracks"] == [
        {"index": 0, "language": "eng", "codec": "subrip", "default": False, "forced": False, "external": False,
         "text": True},
        {"index": 1, "language": "eng", "codec": "hdmv_pgs_subtitle", "default": False, "forced": True,
         "external": False, "text": False},
        {"index": 2, "language": "en", "title": "Movie.en.srt", "codec": "subrip", "default": False,
         "forced": False, "external": True, "text": True},
    ]


@pytest.mark.asyncio
async def test_a_segment_s_cues_are_served_as_webvtt(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.get("/stream/s1/subtitles/0/segment_3.vtt")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/vtt")
    assert response.text == VTT
    assert stream.calls == [("get_subtitle_segment", ("s1", 0, 3))]


@pytest.mark.asyncio
async def test_a_download_s_subtitle_track_is_served_whole(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.get(f"/download/{TASK}/subtitles/2.vtt", params={"file_index": 1})

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/vtt")
    assert stream.calls == [("subtitle_file", (TASK, 1, 2))]


@pytest.mark.asyncio
async def test_a_subtitle_file_is_served_whole(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    """#101: a file beside the video comes whole, not by the segment."""
    response = await client.get("/stream/s1/subtitles/2.vtt")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/vtt")
    assert stream.calls == [("get_subtitle_file", ("s1", 2))]
