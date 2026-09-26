"""A site download's subtitles, saved beside its file (#102)."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path

from loguru import logger

from src.core.error import Error
from src.lib.site.client import SiteClient
from src.lib.site.filename import subtitle_filename
from src.lib.site.subtitles import fetch_subtitle

#: Reads a site's subtitle file with the headers its server expects.
SubtitleFetcher = Callable[[str, Mapping[str, str]], Awaitable[bytes]]


async def save_site_subtitles(
    client: SiteClient,
    page_url: str,
    destination: Path,
    fetch: SubtitleFetcher = fetch_subtitle,
) -> list[Path]:
    """Save the page's own subtitles, and its captions in the language it was spoken in, beside ``destination``.

    The same tracks the player offers for the page, so a finished download
    shows them through #101. Each is best-effort: one that fails is logged
    and skipped. They land in the task's own folder, so deleting its files
    deletes them, and forgetting the task keeps them.
    """
    info = await client.extract(page_url)
    saved: list[Path] = []
    for subtitle in info.subtitles:
        path = destination.with_name(
            subtitle_filename(destination.name, subtitle.language, subtitle.ext, automatic=subtitle.automatic)
        )
        try:
            raw = await fetch(subtitle.url, subtitle.headers)
            await asyncio.to_thread(path.write_bytes, raw)
        except (Error, OSError) as error:
            logger.warning("site_subtitles|{} {} not saved: {}", page_url, subtitle.language, error)
            continue
        saved.append(path)
    return saved
