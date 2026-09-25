"""A source's subtitle tracks (#100)."""

from __future__ import annotations

from dataclasses import dataclass

#: What ffmpeg turns into WebVTT. ASS and SSA lose their styling on the way.
TEXT_CODECS = frozenset({"subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text"})


@dataclass(frozen=True, slots=True)
class SubtitleTrack:
    #: Counted among the source's subtitle tracks only, as ``-map 0:s:N`` counts.
    index: int
    language: str | None = None
    title: str | None = None
    codec: str | None = None
    default: bool = False
    #: Shown whether or not subtitles are on: signs, and lines in another language.
    forced: bool = False
    #: A file beside the video rather than a track inside it (#101): served
    #: whole, never by the segment. Numbered after the embedded tracks.
    external: bool = False

    @property
    def text(self) -> bool:
        """Whether it can be shown: a picture track (PGS, VobSub) can't become WebVTT."""
        return self.codec in TEXT_CODECS
