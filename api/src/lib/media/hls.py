"""HLS media playlists: reading one, and writing one of just the fragments a segment needs.

The player cuts each segment with its own ffmpeg run. Seeking into an HLS
input drops every stream's packets until a keyframe at or past the target,
which clips a TS segment's audio and misreads fMP4 (#87). A playlist of only
the fragments that overlap the segment has ffmpeg read those from their start,
and fetch nothing else.

Pure: no network, no files.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from urllib.parse import urljoin

#: ``NAME=value`` pairs of an attribute list. A quoted value keeps its commas.
_ATTRIBUTE = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')

#: What ffmpeg's HLS demuxer decrypts whole. It reads SAMPLE-AES only in part.
_KEY_METHODS = ("NONE", "AES-128")


@dataclass(frozen=True, slots=True)
class ByteRange:
    length: int
    #: Always resolved when the playlist is read, never implied: a cut that
    #: starts mid-way has no previous range to follow on from.
    offset: int


@dataclass(frozen=True, slots=True)
class InitSection:
    """An ``EXT-X-MAP``: the header an fMP4 fragment needs before it decodes."""

    url: str
    byterange: ByteRange | None


@dataclass(frozen=True, slots=True)
class Fragment:
    #: Seconds from the start of the first fragment.
    start: float
    duration: float
    url: str
    #: Its media sequence number, which AES-128 without an IV decrypts with.
    sequence: int
    byterange: ByteRange | None
    #: The ``EXT-X-MAP`` in effect.
    init: InitSection | None
    #: The ``EXT-X-KEY`` line in effect, its URI made absolute. ``None`` when unencrypted.
    key: str | None
    #: An ``EXT-X-DISCONTINUITY`` came right before it.
    discontinuity: bool


@dataclass(frozen=True, slots=True)
class MediaPlaylist:
    version: int
    fragments: tuple[Fragment, ...]

    @property
    def duration(self) -> float:
        last = self.fragments[-1]
        return last.start + last.duration


class PlaylistRefused(Exception):
    """A playlist the player can't cut segments from."""

    def __init__(self, reason: str, *, live: bool = False) -> None:
        super().__init__(reason)
        #: It has no end yet: a live stream, or one still being written.
        self.live = live


def _attributes(text: str) -> dict[str, str]:
    return dict(_ATTRIBUTE.findall(text))


def _unquoted(value: str) -> str:
    return value[1:-1] if len(value) >= 2 and value[0] == value[-1] == '"' else value


def _byterange(text: str, implied_offset: int) -> ByteRange:
    length, _, offset = text.partition("@")
    return ByteRange(int(length), int(offset) if offset else implied_offset)


def _key(attributes: dict[str, str], base: str) -> str | None:
    method = attributes.get("METHOD", "NONE")
    if method not in _KEY_METHODS:
        raise PlaylistRefused(f"{method} encryption")
    if method == "NONE":
        return None
    absolute = {**attributes, "URI": f'"{urljoin(base, _unquoted(attributes["URI"]))}"'}
    return "#EXT-X-KEY:" + ",".join(f"{name}={value}" for name, value in absolute.items())


def _init(attributes: dict[str, str], base: str) -> InitSection:
    ranged = attributes.get("BYTERANGE")
    url = urljoin(base, _unquoted(attributes["URI"]))
    # A MAP's range with no offset starts at the top of its file.
    return InitSection(url, _byterange(_unquoted(ranged), 0) if ranged else None)


def _read(url: str, text: str) -> tuple[int, list[Fragment], bool]:
    """The version, the fragments, and whether the playlist has ended."""
    version, sequence, elapsed = 3, 0, 0.0
    init: InitSection | None = None
    key: str | None = None
    duration: float | None = None
    byterange: str | None = None
    discontinuity, ended = False, False
    #: Where each resource's next implied byte range starts.
    ends: dict[str, int] = {}
    fragments: list[Fragment] = []

    for line in (raw.strip() for raw in text.splitlines()):
        tag, _, value = line.partition(":")
        if tag == "#EXT-X-STREAM-INF":
            raise PlaylistRefused("a master playlist")
        if tag == "#EXT-X-VERSION":
            version = int(value)
        elif tag == "#EXT-X-MEDIA-SEQUENCE":
            sequence = int(value)
        elif tag == "#EXT-X-KEY":
            key = _key(_attributes(value), url)
        elif tag == "#EXT-X-MAP":
            init = _init(_attributes(value), url)
        elif tag == "#EXTINF":
            duration = float(value.split(",", 1)[0])
        elif tag == "#EXT-X-BYTERANGE":
            byterange = value
        elif tag == "#EXT-X-DISCONTINUITY":
            discontinuity = True
        elif tag == "#EXT-X-ENDLIST":
            ended = True
        elif line and not line.startswith("#") and duration is not None:
            fragment_url = urljoin(url, line)
            ranged = None
            if byterange is not None:
                ranged = _byterange(byterange, ends.get(fragment_url, 0))
                ends[fragment_url] = ranged.offset + ranged.length
            fragments.append(Fragment(elapsed, duration, fragment_url, sequence, ranged, init, key, discontinuity))
            elapsed += duration
            sequence += 1
            duration, byterange, discontinuity = None, None, False
    return version, fragments, ended


def parse_media_playlist(url: str, text: str) -> MediaPlaylist:
    """A media playlist's fragments, every URI resolved against ``url``, the one it was fetched from."""
    try:
        version, fragments, ended = _read(url, text)
    except (KeyError, ValueError) as exc:
        raise PlaylistRefused(f"unreadable: {exc}") from exc
    if not fragments:
        raise PlaylistRefused("no fragments")
    if not ended:
        raise PlaylistRefused("no end", live=True)
    return MediaPlaylist(version, tuple(fragments))


def _range(byterange: ByteRange) -> str:
    return f"{byterange.length}@{byterange.offset}"


def _map(init: InitSection) -> str:
    line = f'#EXT-X-MAP:URI="{init.url}"'
    return f'{line},BYTERANGE="{_range(init.byterange)}"' if init.byterange is not None else line


def sub_playlist(playlist: MediaPlaylist, start: float, end: float) -> tuple[str, float]:
    """A playlist of the fragments overlapping ``start``..``end``, and when the first of them starts.

    A window past the last fragment gets the last one: the final segment can
    overshoot a playlist by a rounding difference, and must not come out empty.
    """
    chosen = [f for f in playlist.fragments if f.start + f.duration > start and f.start < end]
    if not chosen:
        chosen = [playlist.fragments[-1]]
    lines = [
        "#EXTM3U",
        f"#EXT-X-VERSION:{playlist.version}",
        f"#EXT-X-TARGETDURATION:{math.ceil(max(f.duration for f in chosen))}",
        # The first fragment's own number, so AES-128 without an IV still
        # decrypts each fragment with the right one.
        f"#EXT-X-MEDIA-SEQUENCE:{chosen[0].sequence}",
        "#EXT-X-PLAYLIST-TYPE:VOD",
    ]
    previous: Fragment | None = None
    for fragment in chosen:
        if previous is not None and fragment.discontinuity:
            lines.append("#EXT-X-DISCONTINUITY")
        if fragment.init is not None and (previous is None or fragment.init != previous.init):
            lines.append(_map(fragment.init))
        if fragment.key != (previous.key if previous is not None else None):
            lines.append(fragment.key or "#EXT-X-KEY:METHOD=NONE")
        if fragment.byterange is not None:
            lines.append(f"#EXT-X-BYTERANGE:{_range(fragment.byterange)}")
        lines += [f"#EXTINF:{fragment.duration:.6f},", fragment.url]
        previous = fragment
    lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n", chosen[0].start
