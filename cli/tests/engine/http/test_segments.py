import os
from pathlib import Path

import httpx
import pytest
from src.core.errors import DownloadError
from src.engine.http import segments as seg_mod
from src.engine.http.resume import Segment
from src.engine.http.segments import NoRanges, download_segments

DATA = bytes(range(256)) * 4


@pytest.fixture(autouse=True)
def _no_backoff(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(seg_mod, "BACKOFF", 0.0)


async def run(handler, tmp_path: Path, validator: str | None = None, save=lambda: None) -> list[Segment]:
    segs = [Segment(0, len(DATA) - 1)]
    fd = os.open(tmp_path / "f", os.O_RDWR | os.O_CREAT)
    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await download_segments(client, "http://h/f", fd, segs, {}, lambda: None, save, validator)
    finally:
        os.close(fd)
    return segs


def serve_range(request: httpx.Request, start_shift: int = 0) -> httpx.Response:
    start, _, end = request.headers["range"].removeprefix("bytes=").partition("-")
    s, e = int(start), int(end)
    return httpx.Response(
        206, headers={"content-range": f"bytes {s + start_shift}-{e}/{len(DATA)}"}, stream=httpx.ByteStream(DATA[s : e + 1])
    )


async def test_sends_if_range_with_the_validator(tmp_path: Path) -> None:
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("if-range"))
        return serve_range(request)

    await run(handler, tmp_path, '"v1"')
    assert seen == ['"v1"']


async def test_wrong_content_range_start_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(DownloadError, match="range"):
        await run(lambda r: serve_range(r, start_shift=1), tmp_path)


async def test_200_with_a_different_validator_means_the_file_changed(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"etag": '"v2"'}, stream=httpx.ByteStream(DATA))

    with pytest.raises(DownloadError, match="changed"):
        await run(handler, tmp_path, '"v1"')


async def test_200_with_the_same_validator_means_no_range_support(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"etag": '"v1"'}, stream=httpx.ByteStream(DATA))

    with pytest.raises(NoRanges):
        await run(handler, tmp_path, '"v1"')


async def test_transient_errors_are_retried_from_where_the_segment_stopped(tmp_path: Path) -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.headers["range"])
        if len(calls) == 1:
            raise httpx.ConnectError("boom")
        if len(calls) == 2:
            return httpx.Response(503)
        return serve_range(request)

    segs = await run(handler, tmp_path)
    assert segs[0].done == len(DATA)
    assert len(calls) == 3
    assert (tmp_path / "f").read_bytes() == DATA


async def test_client_errors_are_not_retried(tmp_path: Path) -> None:
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(403)

    with pytest.raises(httpx.HTTPStatusError):
        await run(handler, tmp_path)
    assert calls == [1]


async def test_gives_up_after_a_few_attempts(tmp_path: Path) -> None:
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(503)

    with pytest.raises(httpx.HTTPStatusError):
        await run(handler, tmp_path)
    assert len(calls) == 3


async def test_data_is_fsynced_before_each_record_save(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    events: list[str] = []
    monkeypatch.setattr(seg_mod.os, "fsync", lambda fd: events.append("fsync"))
    await run(serve_range, tmp_path, save=lambda: events.append("save"))
    assert events and events[0] == "fsync"
    assert all(events[i] == "fsync" for i in range(len(events)) if events[i + 1 : i + 2] == ["save"])
    assert events.count("fsync") >= events.count("save")
