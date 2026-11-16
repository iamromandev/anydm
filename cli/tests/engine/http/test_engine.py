import asyncio
import hashlib
import os
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.core.progress import NullReporter
from src.engine import Job
from src.engine.http import resume
from src.engine.http.engine import HttpEngine

MIB = 1 << 20


class Notices(NullReporter):
    def __init__(self) -> None:
        self.messages: list[str] = []

    def notice(self, msg: str) -> None:
        self.messages.append(msg)


def make_data(size: int) -> bytes:
    return os.urandom(size)


def job(url: str, dest: Path, connections: int = 4) -> Job:
    return Job(url, dest, connections, None, False, False, False)


async def download(url: str, dest: Path, connections: int = 4, report: NullReporter | None = None) -> list[Path]:
    return await HttpEngine().run(job(url, dest, connections), report or NullReporter())


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


async def test_downloads_over_four_ranges_and_matches(serve, tmp_path: Path) -> None:
    data = make_data(5 * MIB)
    srv = serve(data)
    [path] = await download(srv.url(), tmp_path)
    assert path == (tmp_path / "file.bin").resolve()
    assert path.read_bytes() == data
    ranged = [r for r in srv.gets() if r.range and r.range != "bytes=0-0"]
    assert len(ranged) == 4


async def test_no_range_support_uses_one_plain_get(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data, ranges=False)
    [path] = await download(srv.url(), tmp_path)
    assert path.read_bytes() == data
    assert [r.range for r in srv.gets()] == [None]


async def test_ranges_answered_with_200_fall_back_to_single_stream(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data, range_as_200=True)
    report = Notices()
    [path] = await download(srv.url(), tmp_path, report=report)
    assert path.read_bytes() == data
    assert any("single stream" in m for m in report.messages)


async def test_unknown_length_streams_the_whole_body(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data, length=False, ranges=False)
    [path] = await download(srv.url(), tmp_path)
    assert path.read_bytes() == data
    assert list(tmp_path.glob("*.part*")) == []


async def test_head_not_allowed_falls_back_to_range_get(serve, tmp_path: Path) -> None:
    data = make_data(2 * MIB)
    srv = serve(data, head=False)
    [path] = await download(srv.url(), tmp_path)
    assert path.read_bytes() == data


async def test_empty_file(serve, tmp_path: Path) -> None:
    srv = serve(b"")
    [path] = await download(srv.url(), tmp_path)
    assert path.read_bytes() == b""


async def test_follows_redirects_and_names_from_final_url(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data, redirects={"/start": "/final/real.iso"})
    [path] = await download(srv.url("/start"), tmp_path)
    assert path.name == "real.iso"
    assert path.read_bytes() == data


async def test_content_disposition_names_the_file_and_repeats_get_a_counter(serve, tmp_path: Path) -> None:
    srv = serve(make_data(1000), disposition='attachment; filename="a.bin"')
    dest = tmp_path / "missing" / "dir"
    first = await download(srv.url(), dest)
    second = await download(srv.url(), dest)
    assert sorted(p.name for p in dest.iterdir()) == ["a (1).bin", "a.bin"]
    assert [p.name for p in first + second] == ["a.bin", "a (1).bin"]


async def test_hostile_disposition_name_stays_in_the_folder(serve, tmp_path: Path) -> None:
    srv = serve(make_data(100), disposition='attachment; filename="../../etc/x"')
    dest = tmp_path / "out"
    [path] = await download(srv.url(), dest)
    assert path == (dest / "x").resolve()
    assert [p.name for p in tmp_path.iterdir()] == ["out"]


async def test_name_falls_back_to_download(serve, tmp_path: Path) -> None:
    srv = serve(make_data(10))
    [path] = await download(srv.url("/"), tmp_path)
    assert path.name == "download"


async def test_404_is_a_download_error_with_the_status(serve, tmp_path: Path) -> None:
    srv = serve(b"x", missing={"/nope"})
    with pytest.raises(DownloadError, match="404"):
        await download(srv.url("/nope"), tmp_path)


async def test_unreachable_host_is_a_download_error(tmp_path: Path) -> None:
    with pytest.raises(DownloadError):
        await download("http://127.0.0.1:1/x", tmp_path)


async def _interrupt_at_half(srv, url: str, dest: Path, size: int) -> None:
    task = asyncio.create_task(download(url, dest))
    while srv.sent < size // 2 and not task.done():
        await asyncio.sleep(0.02)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


async def test_interrupted_download_resumes_with_only_missing_ranges(serve, tmp_path: Path) -> None:
    size = 8 * MIB
    data = make_data(size)
    srv = serve(data, delay=0.02)
    await _interrupt_at_half(srv, srv.url(), tmp_path, size)
    final = tmp_path / "file.bin"
    assert not final.exists()
    assert resume.part_path(final).exists()
    assert resume.record_path(final).exists()

    srv.delay = 0
    srv.log.clear()
    srv.sent = 0
    report = Notices()
    [path] = await download(srv.url(), tmp_path, report=report)
    assert sha(path) == hashlib.sha256(data).hexdigest()
    assert any("resuming" in m for m in report.messages)
    assert srv.sent < size  # fewer bytes than the whole file
    assert not resume.record_path(final).exists()
    assert not resume.part_path(final).exists()
    assert not resume.lock_path(final).exists()
    assert [p.name for p in tmp_path.iterdir()] == ["file.bin"]


async def test_changed_etag_restarts_with_a_notice(serve, tmp_path: Path) -> None:
    size = 4 * MIB
    srv = serve(make_data(size), delay=0.02)
    await _interrupt_at_half(srv, srv.url(), tmp_path, size)
    new = make_data(size)
    srv.data, srv.etag, srv.delay = new, '"v2"', 0
    report = Notices()
    [path] = await download(srv.url(), tmp_path, report=report)
    assert path.name == "file.bin"
    assert path.read_bytes() == new
    assert any("starting over" in m for m in report.messages)


async def test_second_run_on_a_download_in_progress_fails(serve, tmp_path: Path) -> None:
    size = 4 * MIB
    srv = serve(make_data(size), delay=0.05)
    first = asyncio.create_task(download(srv.url(), tmp_path))
    while srv.sent == 0:
        await asyncio.sleep(0.02)
    with pytest.raises(DownloadError, match="already in progress"):
        await download(srv.url(), tmp_path)
    first.cancel()
    with pytest.raises(asyncio.CancelledError):
        await first


async def test_unknown_record_version_starts_over(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data)
    final = tmp_path / "file.bin"
    resume.part_path(final).write_bytes(b"junk")
    resume.record_path(final).write_text('{"v": 99}')
    [path] = await download(srv.url(), tmp_path)
    assert path == (tmp_path / "file.bin").resolve()
    assert path.read_bytes() == data


async def test_resume_uses_the_freshly_probed_final_url(serve, tmp_path: Path) -> None:
    size = 4 * MIB
    srv = serve(make_data(size), delay=0.02, redirects={"/start": "/a/file.bin"})
    await _interrupt_at_half(srv, srv.url("/start"), tmp_path, size)
    srv.redirects = {"/start": "/b/file.bin"}
    srv.missing = {"/a/file.bin"}  # the old signed URL expired
    srv.delay = 0
    [path] = await download(srv.url("/start"), tmp_path)
    assert path.read_bytes() == srv.data


async def test_single_stream_partial_says_it_cannot_resume(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data)
    final = tmp_path / "file.bin"
    resume.part_path(final).write_bytes(b"junk")
    resume.write(final, resume.Record(srv.url(), srv.url(), MIB, srv.etag, srv.last_modified, []))
    report = Notices()
    [path] = await download(srv.url(), tmp_path, report=report)
    assert path.read_bytes() == data
    assert any("cannot resume a single-stream download; starting over" in m for m in report.messages)


async def test_resume_keeps_the_record_in_place(serve, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    size = 4 * MIB
    srv = serve(make_data(size), delay=0.02)
    await _interrupt_at_half(srv, srv.url(), tmp_path, size)
    final = tmp_path / "file.bin"
    removed: list[Path] = []
    real = Path.unlink
    monkeypatch.setattr(Path, "unlink", lambda self, *a, **k: (removed.append(self), real(self, *a, **k))[1])
    srv.delay = 0
    await download(srv.url(), tmp_path)
    # the record is only deleted once, after the download is complete
    assert removed.count(resume.record_path(final)) == 1


async def test_orphan_part_without_record_is_reused_not_leaked(serve, tmp_path: Path) -> None:
    data = make_data(MIB)
    srv = serve(data)
    resume.part_path(tmp_path / "file.bin").write_bytes(b"orphan")
    [path] = await download(srv.url(), tmp_path)
    assert path.name == "file.bin"
    assert [p.name for p in tmp_path.iterdir()] == ["file.bin"]


async def test_concurrent_downloads_of_different_urls_with_one_name_both_succeed(serve, tmp_path: Path) -> None:
    a = serve(make_data(4 * MIB), delay=0.05, disposition='attachment; filename="same.bin"')
    b = serve(make_data(MIB), disposition='attachment; filename="same.bin"')
    first = asyncio.create_task(download(a.url(), tmp_path))
    while a.sent == 0:
        await asyncio.sleep(0.02)
    [second] = await download(b.url(), tmp_path)
    assert second.name == "same (1).bin"
    assert second.read_bytes() == b.data
    a.delay = 0
    [one] = await first
    assert one.name == "same.bin"
    assert one.read_bytes() == a.data


async def test_never_overwrites_a_file_that_appears_during_the_download(serve, tmp_path: Path) -> None:
    size = 4 * MIB
    srv = serve(make_data(size), delay=0.02)
    task = asyncio.create_task(download(srv.url(), tmp_path))
    while srv.sent == 0:
        await asyncio.sleep(0.01)
    (tmp_path / "file.bin").write_bytes(b"precious")
    srv.delay = 0
    [path] = await task
    assert (tmp_path / "file.bin").read_bytes() == b"precious"
    assert path.name == "file (1).bin"
    assert path.read_bytes() == srv.data
    assert sorted(p.name for p in tmp_path.iterdir()) == ["file (1).bin", "file.bin"]


async def test_long_server_name_still_fits_every_derived_file(serve, tmp_path: Path) -> None:
    srv = serve(make_data(MIB), disposition=f'attachment; filename="{"x" * 300}.bin"')
    [path] = await download(srv.url(), tmp_path)
    assert path.read_bytes() == srv.data


class Untouchable:
    """A stand-in for a cookies path that fails the test as soon as anything looks at it."""

    def __getattribute__(self, name: str) -> object:
        raise AssertionError(f"the engine touched job.cookies.{name}")


async def test_a_job_with_cookies_sends_no_cookie_header_and_never_reads_them(serve, tmp_path: Path) -> None:
    srv = serve(b"abc")
    cookie_job = Job(srv.url(), tmp_path, 2, None, False, False, False, Untouchable())  # ty: ignore[invalid-argument-type]
    [path] = await HttpEngine().run(cookie_job, NullReporter())
    assert path.read_bytes() == b"abc"
    assert srv.log and all(r.cookie is None for r in srv.log)


def test_the_torrent_engine_has_no_code_that_reads_cookies() -> None:
    import src.engine.torrent as torrent

    sources = list(Path(torrent.__file__).parent.rglob("*.py"))
    assert sources
    assert [p.name for p in sources if "cookies" in p.read_text()] == []
