"""Subtitle files that sit beside a video (#101). Pure: paths in, matches out.

Torrents ship them as ``Movie.en.srt`` next to the film, as ``Subs/2_English.srt``
below it, or, in a season pack, as ``Subs/Show.S01E02/2_English.srt``. A file
matches a video when it's in the video's folder or a subtitles folder there,
and either its name starts with the video's name, it's in a subtitles folder
named after the video, or it's loose in a subtitles folder beside a video
that's the only one in its folder.

Paths are POSIX and relative: a torrent's file list, or a folder's listing.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from src.lib.media.audio import language_in_name

SUBTITLE_EXTENSIONS = (".srt", ".vtt", ".ass", ".ssa")

#: The media a folder can hold, as ``torrent_source.MEDIA_EXTENSIONS`` lists it.
_VIDEO_EXTENSIONS = (".mp4", ".mkv", ".webm", ".avi", ".mov", ".wmv", ".flv", ".m4v")

_SUBTITLE_FOLDERS = {"subs", "sub", "subtitles", "subtitle"}

#: What separates the words of a release's file name.
_WORDS = re.compile(r"[\s._\-\[\]()]+")

#: Words that say a track is for the hard of hearing.
_HEARING = {"sdh", "cc", "hi"}


@dataclass(frozen=True, slots=True)
class Sidecar:
    #: As given: relative to whatever the paths were relative to.
    path: str
    #: ISO 639-1, from a code or a name in the file name.
    language: str | None
    #: The file's own name, which is what tells two English files apart.
    title: str
    #: Its name says it's forced: signs, and lines in another language.
    forced: bool = False
    #: Its name says it's for the hard of hearing.
    hearing_impaired: bool = False


@dataclass(frozen=True, slots=True)
class TorrentFile:
    """A torrent's file, read through rqbit's stream: one not on disk (yet), selected or not (#93)."""

    info_hash: str
    index: int


@dataclass(frozen=True, slots=True)
class SiteSubtitleFile:
    """A site's own subtitles (#102), fetched with its headers: ``SiteSubtitle``'s place among the sources."""

    url: str
    headers: tuple[tuple[str, str], ...] = ()
    #: Which of the page's tracks, to find again when the URL has expired.
    language: str = ""
    automatic: bool = False


#: Where a subtitle file is read from: the disk, rqbit, or a site.
SidecarSource = Path | TorrentFile | SiteSubtitleFile

#: The codec each kind of file reads as, for the menu and ``SubtitleTrack.text``.
_CODECS = {".srt": "subrip", ".vtt": "webvtt", ".ass": "ass", ".ssa": "ssa"}


def sidecar_codec(path: str) -> str:
    return _CODECS.get(PurePosixPath(path).suffix.lower(), "subrip")


def folder_listing(folder: Path) -> list[str]:
    """What sits in ``folder`` that a video there could have beside it: its files, and its subtitle folders'.

    Relative POSIX paths, two levels into a subtitles folder and no further,
    so a downloads folder full of other things costs one listing and a few.
    """
    found: list[str] = []
    for entry in folder.iterdir():
        if entry.is_file():
            found.append(entry.name)
        elif entry.is_dir() and entry.name.lower() in _SUBTITLE_FOLDERS:
            for inner in entry.iterdir():
                if inner.is_file():
                    found.append(f"{entry.name}/{inner.name}")
                elif inner.is_dir():
                    found += [f"{entry.name}/{inner.name}/{leaf.name}" for leaf in inner.iterdir() if leaf.is_file()]
    return found


def is_subtitle_file(path: str) -> bool:
    return path.lower().endswith(SUBTITLE_EXTENSIONS)


def _is_video(path: str) -> bool:
    return path.lower().endswith(_VIDEO_EXTENSIONS)


def match_sidecars(video: str, paths: Sequence[str]) -> list[Sidecar]:
    """The subtitle files among ``paths`` that go with ``video``, in path order."""
    target = PurePosixPath(video)
    folder, stem = target.parent, target.stem.lower()
    # Another video whose name the file's also starts with, and longer: a
    # ``Movie 2.en.srt`` belongs to ``Movie 2.mkv``, not to ``Movie.mkv``.
    rivals = [
        PurePosixPath(path).stem.lower()
        for path in paths
        if _is_video(path) and PurePosixPath(path).parent == folder and PurePosixPath(path) != target
    ]
    alone = not rivals

    found: list[Sidecar] = []
    for path in paths:
        if not is_subtitle_file(path):
            continue
        candidate = PurePosixPath(path)
        name = candidate.stem.lower()
        named = name.startswith(stem) and not any(
            len(rival) > len(stem) and name.startswith(rival) for rival in rivals
        )
        parent = candidate.parent
        if parent == folder:
            matches = named
        elif parent.parent == folder and parent.name.lower() in _SUBTITLE_FOLDERS:
            matches = named or alone
        elif (
            parent.parent.parent == folder
            and parent.parent.name.lower() in _SUBTITLE_FOLDERS
            and parent.name.lower() == stem
        ):
            matches = True
        else:
            matches = False
        if not matches:
            continue

        rest = name[len(stem) :] if name.startswith(stem) else name
        words = [word for word in _WORDS.split(rest) if word]
        language = next((code for code in map(language_in_name, words) if code is not None), None)
        found.append(
            Sidecar(
                path=path,
                language=language,
                title=candidate.name,
                forced="forced" in words,
                hearing_impaired=any(word in _HEARING for word in words),
            )
        )
    return sorted(found, key=lambda sidecar: sidecar.path.lower())


def decode_subtitles(raw: bytes) -> str:
    """A subtitle file's text: UTF-8 (with or without a BOM), else Windows-1252, which never fails.

    Plenty of SRT files aren't UTF-8, and read as UTF-8 their accents turn
    into replacement characters.
    """
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("cp1252", errors="replace")
