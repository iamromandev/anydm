"""File names from page titles. Pure."""

from __future__ import annotations

import re

_UNSAFE = re.compile(r"[^\w\s-]", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
_MAX_STEM = 150


def safe_filename(title: str, suffix: str, extension: str) -> str:
    r"""A filesystem-safe name built from ``title``.

    Non-ASCII letters survive, because Python's ``\w`` is Unicode-aware, so a
    Japanese or Cyrillic title does not collapse to an empty stem. The stem is
    capped so the full path stays inside the 255-byte limit every common
    filesystem enforces.
    """
    stem = _WHITESPACE.sub("_", _UNSAFE.sub("", (title or "").strip())).strip("_")
    stem = stem[:_MAX_STEM] or "download"
    tail = f"_{suffix}" if suffix else ""
    return f"{stem}{tail}.{extension}"


_LANGUAGE = re.compile(r"[^A-Za-z0-9-]")


def subtitle_filename(video: str, language: str, extension: str, *, automatic: bool = False) -> str:
    """The name a site's subtitles are saved under beside ``video`` (#102): ``Title.en.vtt``.

    Machine captions add ``.auto``, which #101's matching reads as no
    language, so ``Title.en.auto.vtt`` is still English.
    """
    stem = video.rsplit(".", 1)[0] if "." in video else video
    code = _LANGUAGE.sub("", language)[:20] or "und"
    kind = ".auto" if automatic else ""
    return f"{stem}.{code}{kind}.{extension}"
