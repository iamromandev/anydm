"""Test helpers: #53's recorded sites as ``SiteInfo``, and a client that serves them.

The fixtures carry no URLs or header values, so this adds made-up ones that
tests can recognise.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from dataclasses import replace
from pathlib import Path
from typing import Any

from src.core.error import Error
from src.lib.site import error as site_error
from src.lib.site.client import FormatProgress, Resolved, SiteInfo
from src.lib.site.format import Format

FIXTURES = Path(__file__).parent / "fixtures" / "ytdlp"

#: The headers every made-up format carries.
HEADERS = {"User-Agent": "Mozilla/5.0 (test)", "Referer": "https://site.test/"}


def media_url(site: str, format_id: str) -> str:
    return f"https://media.test/{site}/{format_id}"


def site_info(site: str, **overrides: Any) -> SiteInfo:
    fixture = json.loads((FIXTURES / f"{site}.json").read_text())
    info = SiteInfo(
        extractor=fixture["extractor"],
        id=str(fixture["id"]),
        title=fixture["title"],
        uploader="Someone",
        duration=int(fixture["duration"] or 0),
        thumbnail=f"https://img.test/{site}.jpg",
        webpage_url=fixture["webpage_url"],
        formats=[Format.from_ytdlp(f) for f in fixture["formats"]],
    )
    return replace(info, **overrides)


def sized(info: SiteInfo, sizes: dict[str, int]) -> SiteInfo:
    """``info`` with exact sizes given to some of its formats."""
    return replace(info, formats=[replace(f, size=sizes.get(f.id, f.size)) for f in info.formats])


class FakeSiteClient:
    """Serves one recorded site; records what was asked of it."""

    def __init__(self, info: SiteInfo, *, fail: Error | None = None) -> None:
        self.info = info
        self.fail = fail
        self.extracted: list[str] = []
        self.resolved: list[tuple[str, list[str]]] = []
        self.opened: list[str] = []
        self.downloaded: list[tuple[str, str]] = []
        self.site = info.extractor.lower()

    async def extract(self, url: str) -> SiteInfo:
        self.extracted.append(url)
        if self.fail is not None:
            raise self.fail
        return self.info

    async def open(self, url: str) -> tuple[SiteInfo, dict[str, Resolved]]:
        self.opened.append(url)
        if self.fail is not None:
            raise self.fail
        return self.info, {f.id: self._resolved(f.id) for f in self.info.formats}

    async def resolve(self, url: str, format_ids: Sequence[str]) -> dict[str, Resolved]:
        self.resolved.append((url, list(format_ids)))
        if self.fail is not None:
            raise self.fail
        offered = {f.id for f in self.info.formats}
        missing = [format_id for format_id in format_ids if format_id not in offered]
        if missing:
            raise site_error.no_format_for_preset(missing[0])
        return {format_id: self._resolved(format_id) for format_id in format_ids}

    def download_format(
        self,
        page_url: str,
        format_id: str,
        destination: Path,
        *,
        concurrency: int,
        rate_bps: int,
        on_progress: Callable[[FormatProgress], None],
        should_stop: Callable[[], bool],
    ) -> None:
        self.downloaded.append((page_url, format_id))
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"f" * 10)
        on_progress(FormatProgress(downloaded_bytes=10, total_bytes=10, speed_bps=None, eta_seconds=None))

    def _resolved(self, format_id: str) -> Resolved:
        fmt = next(f for f in self.info.formats if f.id == format_id)
        return Resolved(media_url(self.site, format_id), dict(HEADERS), fragmented=fmt.fragmented)
