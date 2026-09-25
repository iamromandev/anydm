"""The MIME type a browser is asked about before it plays a file itself (#94). Pure.

A browser answers ``canPlayType`` about a container and codec strings. Those
are built here from what ffprobe reports, with one string per codec rather
than the exact profile and level: the answer turns on the codec, and #93's
matrix found these forms answered the way each browser then played. A file
this can't describe gets ``None``, which means a session transcodes it.
"""

from __future__ import annotations

_VIDEO = {
    "h264": "avc1.640028",
    "hevc": "hvc1.1.6.L120.90",
    "av1": "av01.0.08M.08",
    "vp9": "vp09.00.40.08",
    "vp8": "vp8",
}
_AUDIO = {
    "aac": "mp4a.40.2",
    "mp3": "mp3",
    "opus": "opus",
    "vorbis": "vorbis",
    "flac": "flac",
    "ac3": "ac-3",
    "eac3": "ec-3",
}
#: What WebM may carry. Matroska with only these is WebM to a browser.
_WEBM = {"vp8", "vp9", "av1", "opus", "vorbis"}


def media_type(container: str | None, video: str | None, audio: str | None) -> str | None:
    """``video/mp4; codecs="avc1.640028, mp4a.40.2"`` and the like, or ``None``."""
    names = [name for name in (video, audio) if name]
    if not container or not names:
        return None
    formats = set(container.split(","))

    # Containers that name their one codec themselves.
    if "mp3" in formats:
        return "audio/mpeg"
    if "flac" in formats:
        return "audio/flac"
    if "wav" in formats:
        return "audio/wav"

    if (video and video not in _VIDEO) or (audio and audio not in _AUDIO):
        return None
    codecs = ", ".join([*([_VIDEO[video]] if video else []), *([_AUDIO[audio]] if audio else [])])
    kind = "video" if video else "audio"

    if "mp4" in formats:
        return f'{kind}/mp4; codecs="{codecs}"'
    if "matroska" in formats:
        subtype = "webm" if set(names) <= _WEBM else "x-matroska"
        return f'{kind}/{subtype}; codecs="{codecs}"'
    if "ogg" in formats and not video:
        return f'audio/ogg; codecs="{codecs}"'
    return None
