from typing import Any

import httpx
import pytest
from src.core.error import Error
from src.core.type import Code
from src.lib.torrent.client import RqbitClient
from src.lib.torrent.source import TorrentSource

MAGNET = "magnet:?xt=urn:btih:abc"
MIB = 1024 * 1024

DETAILS: dict[str, Any] = {
    "id": 3,
    "details": {
        "info_hash": "abc123",
        "name": "Some Release",
        "output_folder": "/downloads/torrent/abc123",
        "files": [
            {"name": "video.mkv", "components": ["video.mkv"], "length": 900, "included": True},
            {"name": "readme.txt", "components": ["readme.txt"], "length": 100, "included": False},
        ],
    },
    "output_folder": "/downloads/torrent/abc123",
}


def _client(handler: Any, *, metadata_timeout_s: float = 30.0) -> RqbitClient:
    transport = httpx.MockTransport(handler)
    return RqbitClient(
        "http://torrent.test:3030",
        client=httpx.AsyncClient(transport=transport),
        metadata_timeout_s=metadata_timeout_s,
    )


@pytest.mark.asyncio
async def test_resolve_asks_for_a_listing_only() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.content
        return httpx.Response(200, json=DETAILS)

    details = await _client(handler).resolve(TorrentSource(text=MAGNET))

    assert "list_only=true" in seen["url"]
    assert seen["body"] == MAGNET.encode()
    assert details.info_hash == "abc123"
    assert details.name == "Some Release"
    assert [f.index for f in details.files] == [0, 1]
    assert details.files[0].path == "video.mkv"
    assert details.files[0].size_bytes == 900
    assert details.files[1].included is False


@pytest.mark.asyncio
async def test_resolve_posts_raw_bytes_for_a_file() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = request.content
        return httpx.Response(200, json=DETAILS)

    await _client(handler).resolve(TorrentSource(blob=b"d8:announce1:xe"))
    assert seen["body"] == b"d8:announce1:xe"


@pytest.mark.asyncio
async def test_add_sends_selection_and_output_folder() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json=DETAILS)

    await _client(handler).add(
        TorrentSource(text=MAGNET),
        only_files=[2, 0],
        output_folder="/downloads/torrent/abc123",
    )

    assert seen["params"]["only_files"] == "0,2"
    assert seen["params"]["output_folder"] == "/downloads/torrent/abc123"
    assert seen["params"]["overwrite"] == "true"
    assert "list_only" not in seen["params"]


@pytest.mark.asyncio
async def test_add_omits_the_selection_when_every_file_is_wanted() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json=DETAILS)

    await _client(handler).add(TorrentSource(text=MAGNET), only_files=[], output_folder="/d")
    assert "only_files" not in seen["params"]


@pytest.mark.asyncio
async def test_list_progress_maps_every_torrent() -> None:
    payload = {
        "torrents": [
            {
                "info_hash": "abc123",
                "name": "Some Release",
                "stats": {
                    "state": "live",
                    "finished": False,
                    "progress_bytes": 300,
                    "uploaded_bytes": 150,
                    "total_bytes": 1000,
                    "error": None,
                    "file_progress": [300, 0],
                    "live": {
                        "download_speed": {"mbps": 1.0},
                        "upload_speed": {"mbps": 0.0},
                        "time_remaining": {"duration": {"secs": 12, "nanos": 0}},
                        "snapshot": {"peer_stats": {"live": 4}},
                    },
                },
            }
        ]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        assert "with_stats=true" in str(request.url)
        return httpx.Response(200, json=payload)

    rows = await _client(handler).list_progress()

    assert len(rows) == 1
    assert rows[0].info_hash == "abc123"
    assert rows[0].download_bps == MIB
    assert rows[0].peers_connected == 4
    assert rows[0].eta_seconds == 12


@pytest.mark.asyncio
async def test_list_progress_skips_a_torrent_with_no_stats() -> None:
    payload = {"torrents": [{"info_hash": "abc123", "name": "x"}]}
    rows = await _client(lambda _r: httpx.Response(200, json=payload)).list_progress()
    assert rows == []


@pytest.mark.asyncio
async def test_control_verbs_hit_their_paths() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        return httpx.Response(200, json={})

    client = _client(handler)
    await client.pause("abc123")
    await client.start("abc123")
    await client.delete("abc123")
    await client.forget("abc123")

    assert seen == [
        "/torrents/abc123/pause",
        "/torrents/abc123/start",
        # Two ways to stop tracking a torrent, and the difference is the
        # files: `delete` takes them with it, `forget` leaves them on disk.
        "/torrents/abc123/delete",
        "/torrents/abc123/forget",
    ]


@pytest.mark.asyncio
async def test_a_404_becomes_not_found() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text="torrent not found")

    with pytest.raises(Error) as caught:
        await _client(handler).pause("abc123")
    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_a_400_becomes_a_rejection_carrying_the_message() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"human_readable": "invalid magnet link"})

    with pytest.raises(Error) as caught:
        await _client(handler).resolve(TorrentSource(text="magnet:?bad"))
    assert caught.value.code == Code.BAD_GATEWAY
    assert "invalid magnet link" in (caught.value.message or "")


@pytest.mark.asyncio
async def test_a_transport_failure_becomes_unavailable() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    with pytest.raises(Error) as caught:
        await _client(handler).list_progress()
    assert caught.value.code == Code.SERVICE_UNAVAILABLE
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_resolve_timeout_becomes_a_metadata_timeout() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow")

    with pytest.raises(Error) as caught:
        await _client(handler, metadata_timeout_s=30).resolve(TorrentSource(text=MAGNET))
    assert caught.value.code == Code.REQUEST_TIMEOUT
    assert "30s" in (caught.value.message or "")


@pytest.mark.asyncio
async def test_ping_is_true_when_the_engine_answers() -> None:
    assert await _client(lambda _r: httpx.Response(200, json={"torrents": []})).ping() is True


@pytest.mark.asyncio
async def test_ping_is_false_rather_than_raising() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    assert await _client(handler).ping() is False


@pytest.mark.asyncio
async def test_set_rate_limits_posts_both_caps() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = request.url.path
        seen["body"] = request.read()
        return httpx.Response(200, json={})

    await _client(handler).set_rate_limits(download_bps=262144, upload_bps=65536)

    assert seen["method"] == "POST"
    assert seen["path"] == "/torrents/limits"
    assert httpx.Response(200, content=seen["body"]).json() == {
        "download_bps": 262144,
        "upload_bps": 65536,
    }


@pytest.mark.asyncio
async def test_zero_is_sent_as_null_because_rqbit_refuses_zero() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = request.read()
        return httpx.Response(200, json={})

    await _client(handler).set_rate_limits(download_bps=0, upload_bps=0)

    assert httpx.Response(200, content=seen["body"]).json() == {"download_bps": None, "upload_bps": None}
