"""The fragment path as the worker sees it: samples, Stopped, errors, bytes on disk."""

import asyncio
import errno
import threading
import time
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.lib.site import error as site_error
from src.lib.site.client import DownloadStopped, FormatProgress
from src.service.download.downloader import Stopped
from src.service.download.fragment import FragmentDownloader, fragment_limits
from src.service.download.progress import AggregateSample
from src.service.download.rate_limit import Unlimited

PAGE = "https://www.dailymotion.com/video/x8"


class ScriptedClient:
    """Reports each step with a pause between, then fails or writes ``size`` bytes."""

    def __init__(self, steps: list[FormatProgress], *, fail: BaseException | None = None, size: int = 10) -> None:
        self.steps = steps
        self.fail = fail
        self.size = size
        self.calls: list[dict[str, Any]] = []

    def download_format(self, page_url: str, format_id: str, destination: Path, **options: Any) -> None:
        self.calls.append({"page_url": page_url, "format_id": format_id, **options})
        for step in self.steps:
            options["on_progress"](step)
            if options["should_stop"]():
                raise DownloadStopped
            time.sleep(0.02)
        if self.fail is not None:
            raise self.fail
        destination.write_bytes(b"x" * self.size)


class EndlessClient:
    """Keeps reporting until told to stop, and notes that it was."""

    def __init__(self) -> None:
        self.stopped = threading.Event()

    def download_format(self, page_url: str, format_id: str, destination: Path, **options: Any) -> None:
        for step in range(1, 2000):
            options["on_progress"](FormatProgress(step, None, None, None))
            if options["should_stop"]():
                self.stopped.set()
                raise DownloadStopped
            time.sleep(0.005)
        raise AssertionError("never asked to stop")


class RecordingLimiter:
    def __init__(self) -> None:
        self.acquired: list[int] = []

    async def acquire(self, size: int) -> None:
        self.acquired.append(size)


def _downloader(client: Any, limiter: Any = None, **overrides: Any) -> FragmentDownloader:
    options: dict[str, Any] = {"concurrency": 4, "rate_bps": 0, "limiter": limiter or Unlimited(), "poll_s": 0.01}
    options.update(overrides)
    return FragmentDownloader(client, **options)


async def _collect(samples: list[AggregateSample], sample: AggregateSample) -> None:
    samples.append(sample)


async def _ignore(_sample: AggregateSample) -> None:
    return None


STEPS = [
    FormatProgress(100, 1000, 50, 18),
    FormatProgress(600, 1000, 50, 8),
    FormatProgress(1000, 1000, None, 0),
]


@pytest.mark.asyncio
async def test_progress_reaches_the_worker_as_samples(tmp_path: Path) -> None:
    samples: list[AggregateSample] = []

    await _downloader(ScriptedClient(STEPS)).fetch(
        PAGE,
        "hls-1080",
        tmp_path / "video.part",
        on_sample=lambda sample: _collect(samples, sample),
        should_stop=lambda: False,
    )

    assert samples[-1] == AggregateSample(
        downloaded_bytes=1000, total_bytes=1000, progress=100, speed_bps=0, eta_seconds=0, segments=()
    )
    downloaded = [sample.downloaded_bytes for sample in samples]
    assert downloaded == sorted(downloaded)


@pytest.mark.asyncio
async def test_the_bytes_on_disk_are_returned(tmp_path: Path) -> None:
    written = await _downloader(ScriptedClient([], size=7)).fetch(
        PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
    )

    assert written == 7


@pytest.mark.asyncio
async def test_the_format_and_the_cap_settings_reach_yt_dlp(tmp_path: Path) -> None:
    client = ScriptedClient([])

    await _downloader(client, concurrency=1, rate_bps=250_000).fetch(
        PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
    )

    call = client.calls[0]
    assert (call["page_url"], call["format_id"]) == (PAGE, "hls-1080")
    assert (call["concurrency"], call["rate_bps"]) == (1, 250_000)


@pytest.mark.asyncio
async def test_a_stop_reaches_the_worker_as_the_engine_s_stopped(tmp_path: Path) -> None:
    with pytest.raises(Stopped):
        await _downloader(ScriptedClient(STEPS)).fetch(
            PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: True
        )


@pytest.mark.asyncio
async def test_nothing_is_reported_once_a_stop_is_asked(tmp_path: Path) -> None:
    # A pause writes the row's last numbers itself. A sample after it, while
    # the thread has yet to notice, put a speed back on a paused task.
    samples: list[AggregateSample] = []
    stop = threading.Event()

    async def note(sample: AggregateSample) -> None:
        samples.append(sample)
        stop.set()

    with pytest.raises(Stopped):
        await _downloader(EndlessClient()).fetch(
            PAGE, "hls-1080", tmp_path / "video.part", on_sample=note, should_stop=stop.is_set
        )

    assert len(samples) == 1


@pytest.mark.asyncio
async def test_cancelling_the_worker_stops_the_thread_at_its_next_progress(tmp_path: Path) -> None:
    client = EndlessClient()
    fetch = asyncio.create_task(
        _downloader(client).fetch(
            PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
        )
    )
    await asyncio.sleep(0.05)

    fetch.cancel()
    with pytest.raises(asyncio.CancelledError):
        await fetch

    assert await asyncio.to_thread(client.stopped.wait, 2.0)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [OSError(errno.ENOSPC, "No space left on device"), site_error.transfer_failed("fragment 3")],
    ids=["disk-full", "transfer"],
)
async def test_errors_from_the_thread_reach_the_worker_unchanged(failure: BaseException, tmp_path: Path) -> None:
    with pytest.raises(type(failure)) as caught:
        await _downloader(ScriptedClient(STEPS[:1], fail=failure)).fetch(
            PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
        )

    assert caught.value is failure
    assert isinstance(failure, (OSError, Error))


@pytest.mark.asyncio
async def test_every_byte_is_charged_to_the_shared_limiter(tmp_path: Path) -> None:
    limiter = RecordingLimiter()

    await _downloader(ScriptedClient(STEPS), limiter=limiter).fetch(
        PAGE, "hls-1080", tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
    )

    assert sum(limiter.acquired) == 1000
    assert all(size > 0 for size in limiter.acquired)


def test_without_a_cap_fragments_download_as_many_at_a_time_as_segments() -> None:
    assert fragment_limits(0, workers=2, segments=4) == (4, 0)


def test_with_a_cap_each_download_takes_one_fragment_at_a_time_and_its_share() -> None:
    # yt-dlp's own limit holds only with one fragment at a time (the spec's probe).
    assert fragment_limits(1_000_000, workers=2, segments=4) == (1, 500_000)
