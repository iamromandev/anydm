"""Real sites, over the network: the check that notices a site changing.

The rest of the suite runs on fakes and #53's recorded formats, which is right
for pull requests and blind to a site changing. These go to the sites
themselves, through the calls a download makes, so the first sign of a change
is a red run rather than a failed download.

Marked ``network`` and left out of the default run.
``.github/workflows/live.yml`` runs them weekly, and ``make api-test-live``
runs them by hand. A red run is the cue to bump yt-dlp: see "Keeping sites
working" in the README.
"""

from __future__ import annotations

import asyncio
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import httpx
import pytest
from src.core.error import Error
from src.lib.site.client import YtDlpClient
from src.lib.site.format import select_plan, usable_presets
from src.service.download.downloader import Downloader, Stopped
from src.service.download.fragment import FragmentDownloader
from src.service.download.probe import probe
from src.service.download.progress import AggregateSample, ProgressSample
from src.service.download.rate_limit import Unlimited

pytestmark = pytest.mark.network

#: One page per site, from the URLs #53 found working on 2026-09-24.
DOWNLOADABLE = {
    "youtube": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "vimeo": "http://vimeo.com/channels/keypeele/75629013",
    "soundcloud": "https://soundcloud.com/ethmusic/lostin-powers-she-so-heavy",
    "x": "https://twitter.com/captainamerica/status/719944021058060289",
    "reddit": "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/",
    "dailymotion": "https://geo.dailymotion.com/player.html?video=x89eyek",
    "twitch": "http://www.twitch.tv/riotgames/v/6528877",
}

#: Enough of each part to prove the bytes flow.
FIRST_BYTES = 1 << 20

#: A healthy fetch of ``FIRST_BYTES`` takes well under a second. At the pace
#: YouTube keeps for a request with no range (#72), it takes about 31.
FETCH_LIMIT_S = 20.0

#: What a site says when it refuses this machine rather than the page. YouTube
#: asks cloud IPs, CI runners among them, to prove they are not a bot. Reddit
#: answers them 403 Blocked, which yt-dlp reports as needing an account. Neither
#: says anything about yt-dlp, and no bump clears them.
_REFUSALS = ("not a bot", "account authentication is required")


@contextmanager
def _unless_refused() -> Iterator[None]:
    """Skip, naming the reason, when the site refused this machine rather than the page."""
    try:
        yield
    except Error as error:
        message = (error.message or "").lower()
        if any(refusal in message for refusal in _REFUSALS):
            pytest.skip(f"the site asked this machine to sign in: {error.message}")
        raise


async def _first_bytes(http: httpx.AsyncClient, url: str, headers: dict[str, str], dest: Path) -> int:
    """The start of ``url``, through the engine a download uses, then stop."""
    received = 0

    async def count(sample: ProgressSample) -> None:
        nonlocal received
        received = sample.downloaded_bytes

    downloader = Downloader(http, chunk_size=64 * 1024, flush_interval_ms=0)
    fetch = downloader.fetch(url, dest, headers=headers, on_sample=count, should_stop=lambda: received >= FIRST_BYTES)
    try:
        # A file smaller than FIRST_BYTES simply finishes.
        return await asyncio.wait_for(fetch, timeout=FETCH_LIMIT_S)
    except Stopped:
        return received
    except TimeoutError:
        pytest.fail(f"{received} bytes in {FETCH_LIMIT_S:g} s: throttled, the way #72 was?")


async def _first_fragment_bytes(client: YtDlpClient, page_url: str, format_id: str, dest: Path) -> int:
    """The start of a fragmented format, through yt-dlp's downloader, then stop."""
    received = 0

    async def count(sample: AggregateSample) -> None:
        nonlocal received
        received = sample.downloaded_bytes

    fragments = FragmentDownloader(client, concurrency=4, rate_bps=0, limiter=Unlimited(), poll_s=0.25)
    fetch = fragments.fetch(page_url, format_id, dest, on_sample=count, should_stop=lambda: received >= FIRST_BYTES)
    try:
        return await asyncio.wait_for(fetch, timeout=FETCH_LIMIT_S)
    except Stopped:
        return received
    except TimeoutError:
        pytest.fail(f"{received} bytes in {FETCH_LIMIT_S:g} s: throttled, the way #72 was?")


@pytest.mark.asyncio
@pytest.mark.parametrize("url", DOWNLOADABLE.values(), ids=DOWNLOADABLE.keys())
async def test_a_site_serves_the_start_of_what_a_download_would_fetch(url: str, tmp_path: Path) -> None:
    client = YtDlpClient()
    with _unless_refused():
        info = await client.extract(url)
        # What the add box would start on: Best, or MP3 for an audio-only site.
        presets = usable_presets(info.formats)
        assert presets, "no format a download can fetch"
        plan = select_plan(info.formats, presets[0])
        parts = [part for part in (plan.video, plan.audio) if part is not None]
        # A fresh extraction, as the worker makes for each attempt.
        resolved = await client.resolve(url, [part.id for part in parts])

    async with httpx.AsyncClient(timeout=httpx.Timeout(30.0, read=None), http2=True) as http:
        for part in parts:
            target = resolved[part.id]
            dest = tmp_path / f"{part.id}.part"
            if target.fragmented:
                # Its size is unknown until the end, so the check is that bytes
                # flowed. The stop fires at FIRST_BYTES all the same. yt-dlp
                # reads the page again first, and may be refused there too.
                with _unless_refused():
                    received = await _first_fragment_bytes(client, url, part.id, dest)
                expected = 1
            else:
                probed = await probe(http, target.url, target.headers)
                received = await _first_bytes(http, target.url, target.headers, dest)
                expected = min(FIRST_BYTES, probed.total_bytes or FIRST_BYTES)
            assert received >= expected, f"format {part.id}: {received} of {expected} bytes"
