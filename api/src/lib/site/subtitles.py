"""A site's own subtitles, out of yt-dlp's info dict (#102). Pure, apart from the fetch.

yt-dlp reports a page's ``subtitles`` and its ``automatic_captions``, each as
``{language: [one entry per format]}``. YouTube offers machine captions in a
hundred or so languages, translated from the one it heard, so only that one
is kept: the ``-orig`` key, else the video's own ``language``.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

import httpx

from src.core.error import Error
from src.core.type import Code, ErrorType

#: What ffmpeg reads, most preferred first. json3, srv3 and TTML are skipped.
_READABLE = ("vtt", "srt")

#: Not subtitles: YouTube lists a live stream's replayed chat here.
_NOT_SUBTITLES = {"live_chat", "rechat"}

_TIMEOUT_S = 30.0
_MAX_BYTES = 10 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class SiteSubtitle:
    #: As the site names it: ``en``, ``pt-BR``.
    language: str
    #: The site's own label, "English (auto-generated)" for captions.
    name: str
    #: Machine captions rather than subtitles someone wrote.
    automatic: bool
    #: ``vtt`` or ``srt``.
    ext: str
    url: str
    headers: dict[str, str] = field(default_factory=dict)


def _pick(
    language: str, entries: Any, automatic: bool, headers: Mapping[str, str]
) -> SiteSubtitle | None:
    if not isinstance(entries, list):
        return None
    by_ext = {entry.get("ext"): entry for entry in entries if isinstance(entry, dict) and entry.get("url")}
    ext = next((candidate for candidate in _READABLE if candidate in by_ext), None)
    if ext is None:
        return None
    entry = by_ext[ext]
    name = str(entry.get("name") or language)
    if automatic and "auto" not in name.lower():
        name = f"{name} (auto-generated)"
    return SiteSubtitle(
        language=language,
        name=name,
        automatic=automatic,
        ext=ext,
        url=str(entry["url"]),
        headers=dict(entry.get("http_headers") or headers),
    )


def site_subtitles(info: Mapping[str, Any]) -> list[SiteSubtitle]:
    """A page's own subtitles, then its captions in the language it was spoken in."""
    formats = info.get("formats") or []
    headers = dict(info.get("http_headers") or (formats[0].get("http_headers") if formats else None) or {})
    found: list[SiteSubtitle] = []
    for language, entries in (info.get("subtitles") or {}).items():
        if language in _NOT_SUBTITLES:
            continue
        if (picked := _pick(language, entries, False, headers)) is not None:
            found.append(picked)

    captions = info.get("automatic_captions") or {}
    spoken = [key for key in captions if key.endswith("-orig")]
    if not spoken and info.get("language") in captions:
        spoken = [info["language"]]
    for key in spoken:
        if (picked := _pick(key.removesuffix("-orig"), captions[key], True, headers)) is not None:
            found.append(picked)
    return found


def same_subtitle(a: SiteSubtitle, b: SiteSubtitle) -> bool:
    """The same track across two extractions, whose URLs differ."""
    return (a.language, a.automatic) == (b.language, b.automatic)


async def fetch_subtitle(
    url: str, headers: Mapping[str, str], *, transport: httpx.AsyncBaseTransport | None = None
) -> bytes:
    """A subtitle file off a site, with the headers its server expects.

    A 403 is a 403 (``Code.FORBIDDEN``): an expired URL, which asking the site
    again fixes. Anything else that fails is a retryable 502.
    """
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S, follow_redirects=True, transport=transport) as client:
            response = await client.get(url, headers=dict(headers))
            response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        raise Error.create(
            code=Code.FORBIDDEN if status == 403 else Code.BAD_GATEWAY,
            message=f"The site's subtitles failed with status {status}",
            error_type=ErrorType.EXTERNAL_API_ERROR,
            retry_able=status != 403,
        ) from exc
    except httpx.HTTPError as exc:
        raise Error.create(
            code=Code.BAD_GATEWAY,
            message=f"The site's subtitles could not be read: {exc}",
            error_type=ErrorType.EXTERNAL_API_ERROR,
            retry_able=True,
        ) from exc
    if len(response.content) > _MAX_BYTES:
        raise Error.create(
            code=Code.UNPROCESSABLE_ENTITY,
            message="That's too big to be a subtitle file",
            error_type=ErrorType.UNPROCESSABLE_ENTITY,
        )
    return response.content
