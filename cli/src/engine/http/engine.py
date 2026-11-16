import errno
import fcntl
import os
import time
from pathlib import Path

import httpx

from src.core.errors import DownloadError
from src.core.progress import Progress, Reporter
from src.engine import Job
from src.engine.http import resume
from src.engine.http.probe import HEADERS, ProbeResult, probe, safe_url
from src.engine.http.resume import Record
from src.engine.http.segments import NoRanges, download_segments, if_range, pwrite_all, split

MAX_REDIRECTS = 10


class _Busy(DownloadError):
    """Another anydm holds the lock."""


class _Lock:
    """An exclusive flock on a side file. The file is unlinked on release; to stay safe against that, a
    holder checks after locking that the path still names the file it locked, and retries when not."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._fd: int | None = None

    def acquire(self) -> None:
        while True:
            fd = os.open(self._path, os.O_RDWR | os.O_CREAT, 0o644)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                os.close(fd)
                raise _Busy("already in progress: another anydm is downloading to this file") from None
            try:
                same = os.stat(self._path).st_ino == os.fstat(fd).st_ino
            except FileNotFoundError:
                same = False
            if same:
                self._fd = fd
                return
            os.close(fd)  # the previous holder unlinked it between our open and flock

    def release(self) -> None:
        if self._fd is not None:
            self._path.unlink(missing_ok=True)
            os.close(self._fd)
            self._fd = None

    def __enter__(self) -> None:
        self.acquire()

    def __exit__(self, *exc: object) -> None:
        self.release()


def _candidate(dest: Path, name: str, n: int) -> Path:
    base = Path(name)
    return dest / (name if n == 0 else f"{base.stem} ({n}){base.suffix}")


def _pick_target(dest: Path, name: str, url: str) -> tuple[Path, _Lock]:
    """The final path for this download and the lock on it: the first name that is free, or holds a partial
    download of the same URL (or an orphan partial with no record). Names another anydm is using are skipped."""
    n = 0
    while True:
        final = _candidate(dest, name, n)
        n += 1
        lock = _Lock(resume.lock_path(final))
        try:
            lock.acquire()
        except _Busy:
            rec = resume.read(final)
            if rec is not None and rec.url == url:
                raise
            continue
        part, record = resume.part_path(final), resume.record_path(final)
        if final.exists() and part.exists() and os.path.samefile(part, final):
            _remove(part, record)  # an earlier run was killed between linking the file and tidying up
        elif not part.exists():
            _remove(record)
        rec = resume.read(final)
        if not final.exists() and (not part.exists() or rec is None or rec.url == url):
            return final, lock
        lock.release()


def _publish(part: Path, final: Path) -> Path:
    """Move `part` to `final` without ever replacing a file; when `final` has appeared meanwhile, the next free
    `name (N)` is used. Returns the path it ended up at."""
    n = 0
    while True:
        target = _candidate(final.parent, final.name, n)
        n += 1
        try:
            os.link(part, target)
        except FileExistsError:
            continue
        except OSError:  # no hard links here: fall back to a rename that checks first
            if target.exists():
                continue
            os.rename(part, target)
            return target
        part.unlink()
        return target


def _remove(*paths: Path) -> None:
    for p in paths:
        p.unlink(missing_ok=True)


class _Meter:
    def __init__(self, report: Reporter, name: str, total: int | None) -> None:
        self._report, self._name, self.total = report, name, total
        self._t0 = time.monotonic()
        self._last = 0.0
        self._base = 0

    def start(self, done: int) -> None:
        self._base, self._t0 = done, time.monotonic()

    def update(self, done: int, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._last < 0.1:
            return
        self._last = now
        speed = (done - self._base) / max(now - self._t0, 1e-6)
        self._report.update(Progress(done=done, total=self.total, speed=speed, name=self._name))


class HttpEngine:
    async def run(self, job: Job, report: Reporter) -> list[Path]:
        try:
            return await self._run(job, report)
        except DownloadError:
            raise
        except httpx.HTTPStatusError as e:
            raise DownloadError(f"HTTP {e.response.status_code} for {safe_url(job.source)}") from e
        except httpx.HTTPError as e:
            raise DownloadError(f"{safe_url(job.source)}: {e}") from e
        except OSError as e:
            reason = "disk full" if e.errno == errno.ENOSPC else (e.strerror or str(e))
            raise DownloadError(f"{reason}; partial data kept") from e

    async def _run(self, job: Job, report: Reporter) -> list[Path]:
        dest = job.dest_dir
        dest.mkdir(parents=True, exist_ok=True)
        async with httpx.AsyncClient(
            verify=not job.insecure,
            follow_redirects=True,
            max_redirects=MAX_REDIRECTS,
            timeout=httpx.Timeout(30.0, pool=None),
            limits=httpx.Limits(max_connections=job.connections, max_keepalive_connections=job.connections),
        ) as client:
            info = await probe(client, job.source)
            final, lock = _pick_target(dest, info.name, job.source)
            try:
                return [await self._download(client, job, info, final, report)]
            finally:
                lock.release()

    async def _download(
        self, client: httpx.AsyncClient, job: Job, info: ProbeResult, final: Path, report: Reporter
    ) -> Path:
        final_requested = final
        part = resume.part_path(final)
        rec = resume.read(final)
        if rec is not None and rec.url == job.source and part.exists():
            if not rec.segments:
                report.notice(f"cannot resume a single-stream download; starting over: {final.name}")
                rec = None
            elif rec.matches(info) and part.stat().st_size == rec.size:
                report.notice(f"resuming {final.name}")
                rec.final_url = info.final_url  # redirect targets (signed URLs) expire
            else:
                report.notice(f"{final.name} changed on the server; starting over")
                rec = None
        else:
            rec = None
        if rec is None:
            _remove(resume.record_path(final), part)
            segments = split(info.size, job.connections) if info.ranges and info.size else []
            rec = Record(job.source, info.final_url, info.size, info.etag, info.last_modified, segments)
        resume.write(final, rec)

        meter = _Meter(report, final.name, info.size)
        fd = os.open(part, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            if rec.size is not None:
                os.ftruncate(fd, rec.size)
            try:
                if rec.segments:
                    await self._ranged(client, rec, fd, final, meter)
                else:
                    await self._single(client, rec, fd, final, meter)
            except NoRanges:
                report.notice("server ignored the range request; using a single stream")
                rec.segments = []
                resume.write(final, rec)
                os.ftruncate(fd, 0)
                if rec.size is not None:
                    os.ftruncate(fd, rec.size)
                await self._single(client, rec, fd, final, meter)
            os.fsync(fd)
        finally:
            os.close(fd)
        final = _publish(part, final)
        _remove(resume.record_path(final_requested))
        meter.update(final.stat().st_size, force=True)
        return final.resolve()

    async def _ranged(self, client: httpx.AsyncClient, rec: Record, fd: int, final: Path, meter: _Meter) -> None:
        segs = rec.segments
        meter.start(sum(s.done for s in segs))
        await download_segments(
            client,
            rec.final_url,
            fd,
            segs,
            HEADERS,
            on_progress=lambda: meter.update(sum(s.done for s in segs)),
            save=lambda: resume.write(final, rec),
            validator=if_range(rec.etag, rec.last_modified),
        )

    async def _single(self, client: httpx.AsyncClient, rec: Record, fd: int, final: Path, meter: _Meter) -> None:
        done = 0
        meter.start(0)
        async with client.stream("GET", rec.final_url, headers=HEADERS) as resp:
            resp.raise_for_status()
            async for chunk in resp.aiter_raw():
                pwrite_all(fd, chunk, done)
                done += len(chunk)
                meter.update(done)
        if rec.size is not None and done != rec.size:
            raise DownloadError(f"connection closed early: got {done} of {rec.size} bytes")
        os.ftruncate(fd, done)
