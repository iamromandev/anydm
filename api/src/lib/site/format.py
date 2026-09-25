"""Which of a site's formats a preset means. Pure: no network, no yt-dlp import.

The rule is the one YouTube has always had: the tallest format not over the
preset's height, else the shortest there is, because a request for 480p that is
answered with a 4 GB 4K file has not been answered. A combined format wins when
it is at least as tall as the best video-only one, since it needs no mux.

Ties at the same height go to a plain HTTP(S) format over HLS or DASH, which
the segmented engine fetches faster and resumes by the byte, then to the higher
bitrate.

Three things the #53 recording showed about yt-dlp's formats:

- An unset codec (``None``) means unknown, not absent. Only the string
  ``"none"`` says a track is missing. Vimeo's and X's progressive MP4s have no
  codecs recorded and are combined files.
- ``mhtml`` formats are storyboard images, never media.
- Sizes are often unknown, or only estimated.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any

from src.data.type import Kind, Preset
from src.lib.media.audio import language_key
from src.lib.site.error import no_format_for_preset, stream_not_playable

#: Protocols made of fragments: playlists the segmented engine cannot fetch.
_FRAGMENTED = ("m3u8", "dash", "f4m", "ism")


def is_fragmented(protocol: str | None) -> bool:
    """Whether a yt-dlp protocol is a playlist of fragments, which only yt-dlp's downloader fetches."""
    return any(tag in (protocol or "") for tag in _FRAGMENTED)


#: The height presets, tallest first, in the order they are offered.
_HEIGHTS = (Preset.P2160, Preset.P1440, Preset.P1080, Preset.P720, Preset.P480)

#: yt-dlp names the container; MIME wants the subtype.
_SUBTYPE = {"m4a": "mp4"}


@dataclass(frozen=True, slots=True)
class Format:
    id: str
    protocol: str = "https"
    ext: str = ""
    vcodec: str | None = None
    acodec: str | None = None
    height: int | None = None
    #: Bits per second.
    bitrate: int | None = None
    size: int | None = None
    size_approx: int | None = None
    #: The audio's language, as the site names it (``en``, ``es-US``).
    language: str | None = None
    #: yt-dlp's rank for that language: YouTube gives its original audio 10
    #: and each dub -1, so a dub never wins on bitrate alone (#99).
    language_preference: int | None = None
    audio_channels: int | None = None

    @classmethod
    def from_ytdlp(cls, raw: dict[str, Any]) -> Format:
        """One yt-dlp format dict. ``tbr``/``abr`` are kbit/s."""
        rate = raw.get("tbr") or raw.get("abr")
        return cls(
            id=str(raw.get("format_id") or ""),
            protocol=str(raw.get("protocol") or ""),
            ext=str(raw.get("ext") or ""),
            vcodec=raw.get("vcodec"),
            acodec=raw.get("acodec"),
            height=int(raw["height"]) if raw.get("height") else None,
            bitrate=round(rate * 1000) if rate else None,
            size=raw.get("filesize") or None,
            size_approx=raw.get("filesize_approx") or None,
            language=raw.get("language") or None,
            language_preference=raw.get("language_preference"),
            audio_channels=raw.get("audio_channels") or None,
        )

    @property
    def media(self) -> bool:
        return "mhtml" not in (self.protocol, self.ext)

    @property
    def has_video(self) -> bool:
        if self.vcodec == "none":
            return False
        return self.vcodec is not None or self.height is not None

    @property
    def has_audio(self) -> bool:
        # Unknown audio counts as present: yt-dlp marks a video-only stream's
        # audio "none" explicitly, so an unset one is a combined file or audio.
        return self.acodec != "none"

    @property
    def fragmented(self) -> bool:
        return is_fragmented(self.protocol)

    @property
    def hls(self) -> bool:
        """An HLS playlist: the one fragmented kind the player cuts segments from."""
        return "m3u8" in self.protocol

    @property
    def best_size(self) -> tuple[int | None, bool]:
        """The size to count, and whether it is only an estimate."""
        if self.size:
            return self.size, False
        if self.size_approx:
            return self.size_approx, True
        return None, False


@dataclass(frozen=True, slots=True)
class Plan:
    kind: Kind
    #: The video part; a combined format sits here with no ``audio``.
    video: Format | None
    audio: Format | None
    mime_type: str
    extension: str
    quality: str
    #: The parts' combined size; ``None`` when any part's is unknown.
    expected_bytes: int | None = None
    size_is_estimate: bool = False

    @property
    def fragmented(self) -> bool:
        """Whether any part needs yt-dlp's downloader rather than the engine."""
        return any(part.fragmented for part in (self.video, self.audio) if part is not None)


def _eligible(formats: list[Format]) -> list[Format]:
    return [f for f in formats if f.media]


def _by_height(formats: list[Format]) -> list[Format]:
    return sorted(formats, key=lambda f: (-(f.height or 0), f.fragmented, -(f.bitrate or 0)))


def _by_bitrate(formats: list[Format]) -> list[Format]:
    """The site's own audio first, then the best of it: a dub can have the higher bitrate."""
    return sorted(formats, key=lambda f: (-(f.language_preference or 0), -(f.bitrate or 0), f.fragmented))


def _pick(candidates: list[Format], target: int | None) -> Format | None:
    """The tallest not exceeding ``target``, else the shortest; ``None`` is "best"."""
    if not candidates:
        return None
    if target is None:
        return candidates[0]
    return next((f for f in candidates if (f.height or 0) <= target), candidates[-1])


def _size(*parts: Format) -> tuple[int | None, bool]:
    sizes = [part.best_size for part in parts]
    if any(size is None for size, _ in sizes):
        return None, False
    return sum(size for size, _ in sizes if size is not None), any(estimate for _, estimate in sizes)


def _split(formats: list[Format]) -> tuple[list[Format], list[Format], list[Format]]:
    combined = _by_height([f for f in formats if f.has_video and f.has_audio])
    video_only = _by_height([f for f in formats if f.has_video and not f.has_audio])
    audio_only = _by_bitrate([f for f in formats if f.has_audio and not f.has_video])
    return combined, video_only, audio_only


def _subtype(ext: str) -> str:
    return _SUBTYPE.get(ext, ext) or "mp4"


#: yt-dlp's codec strings, by what comes before the first dot. "avc1.640028"
#: is H.264, "mp4a.40.2" is AAC.
_CODEC_NAMES = {
    "avc1": "h264", "avc3": "h264", "h264": "h264",
    "hev1": "hevc", "hvc1": "hevc", "hevc": "hevc", "h265": "hevc",
    "av01": "av1", "av1": "av1",
    "vp09": "vp9", "vp9": "vp9",
    "vp8": "vp8",
    "mp4a": "aac", "aac": "aac",
    "mp3": "mp3",
    "opus": "opus",
    "vorbis": "vorbis",
    "ac-3": "ac3", "ac3": "ac3",
    "ec-3": "eac3", "eac3": "eac3",
}

#: The containers two parts can be muxed into, most preferred first, each with
#: the video and audio codecs it carries well. MP4 plays in the most places.
#: WebM is where browsers play VP8 and Vorbis: in MP4, ffmpeg refuses VP8
#: outright and writes Vorbis in a way players reject.
_CONTAINERS = (
    ("mp4", "video/mp4", frozenset({"h264", "hevc", "av1", "vp9"}), frozenset({"aac", "mp3", "opus", "ac3", "eac3"})),
    ("webm", "video/webm", frozenset({"vp8", "vp9", "av1"}), frozenset({"opus", "vorbis"})),
)


def _codec(value: str | None) -> str | None:
    if not value:
        return None
    return _CODEC_NAMES.get(value.split(".", 1)[0].lower())


def container_for(vcodec: str | None, acodec: str | None) -> tuple[str, str]:
    """The extension and MIME type for muxing a video part and an audio part.

    MKV when no other fits both, a codec nobody named included: it carries
    anything, so the mux cannot fail on the container's account.
    """
    video, audio = _codec(vcodec), _codec(acodec)
    for extension, mime_type, videos, audios in _CONTAINERS:
        if video in videos and audio in audios:
            return extension, mime_type
    return "mkv", "video/x-matroska"


def select_plan(formats: list[Format], preset: Preset) -> Plan:
    """The plan ``preset`` means for these formats."""
    combined, video_only, audio_only = _split(_eligible(formats))

    if preset == Preset.MP3:
        if not audio_only:
            raise no_format_for_preset(preset.value)
        audio = audio_only[0]
        size, estimate = _size(audio)
        return Plan(Kind.AUDIO, None, audio, "audio/mpeg", "mp3", "mp3", size, estimate)

    target = preset.target_height
    best_combined = _pick(combined, target)
    best_video = _pick(video_only, target)
    best_audio = audio_only[0] if audio_only else None

    if best_combined is not None and (best_video is None or (best_combined.height or 0) >= (best_video.height or 0)):
        size, estimate = _size(best_combined)
        quality = f"{best_combined.height}p" if best_combined.height else preset.value
        if best_combined.fragmented:
            # Remuxed after download anyway (MPEG-TS is not MP4), so into the
            # container its codecs fit.
            extension, mime_type = container_for(best_combined.vcodec, best_combined.acodec)
        else:
            extension = best_combined.ext or "mp4"
            mime_type = f"video/{_subtype(extension)}"
        return Plan(Kind.VIDEO, best_combined, None, mime_type, extension, quality, size, estimate)

    if best_video is not None and best_audio is not None:
        size, estimate = _size(best_video, best_audio)
        quality = f"{best_video.height}p" if best_video.height else preset.value
        extension, mime_type = container_for(best_video.vcodec, best_audio.acodec)
        return Plan(Kind.VIDEO, best_video, best_audio, mime_type, extension, quality, size, estimate)

    raise no_format_for_preset(preset.value)


def usable_presets(formats: list[Format]) -> list[Preset]:
    """The presets these formats can satisfy, in the order they are offered.

    A height preset is offered only up to the tallest format, where it would
    otherwise just repeat "best". An audio-only source offers MP3 alone: its
    "best" would be the untranscoded file, which the post-processor cannot yet
    keep as it is.
    """
    combined, video_only, audio_only = _split(_eligible(formats))
    offered: list[Preset] = []

    if combined or (video_only and audio_only):
        offered.append(Preset.BEST)
        heights = [f.height for f in (*combined, *(video_only if audio_only else [])) if f.height]
        tallest = max(heights, default=0)
        offered.extend(p for p in _HEIGHTS if p.target_height is not None and p.target_height <= tallest)

    if audio_only:
        offered.append(Preset.MP3)
    return offered


#: The tallest the player streams. Segments are transcoded as they are asked
#: for, and 4K on demand costs a great deal for nothing a browser player shows.
PLAYBACK_PRESET = Preset.P1080


def audio_choices(formats: list[Format], plan: Plan) -> list[Format]:
    """The audio tracks the player offers for ``plan``: the best audio-only format in each language (#99).

    Drawn from the kind of format the plan plays, plain or HLS, since both of
    a session's inputs are read the same way. The site's own audio comes
    first. A plan with no separate audio offers none: a combined format's
    audio can't be swapped.
    """
    if plan.audio is None:
        return []
    same_kind = [
        f for f in _eligible(formats)
        if f.has_audio and not f.has_video and (f.hls if plan.audio.hls else not f.fragmented)
    ]
    choices: dict[str | None, Format] = {}
    for candidate in _by_bitrate(same_kind):
        choices.setdefault(language_key(candidate.language), candidate)
    return list(choices.values())


def playback_plan(formats: list[Format], language: str | None = None) -> Plan:
    """What the player streams: video at up to 1080p, or an audio-only site's audio.

    Plain files first, and HLS only when a page has nothing else that plays:
    an HLS segment costs a playlist fetch and a cut (#87). A plan is all one
    or all the other, since the player reads both of its inputs the same way.
    Within each kind, video comes first, so a page's HLS video beats its plain
    audio. A DASH, f4m or ISM URL is a manifest of every rendition, which
    ffmpeg would not narrow to the one chosen. Downloads take all of them.

    Audio in the preferred ``language`` replaces the plan's own when the page
    has some (#99).
    """
    plan = _playback_plan(formats)
    key = language_key(language)
    if key is None:
        return plan
    match = next((f for f in audio_choices(formats, plan) if language_key(f.language) == key), None)
    return replace(plan, audio=match) if match is not None else plan


def _playback_plan(formats: list[Format]) -> Plan:
    plain = [f for f in formats if not f.fragmented]
    hls = [f for f in formats if f.hls]
    for pool in (plain, hls):
        if Preset.BEST in usable_presets(pool):
            return select_plan(pool, PLAYBACK_PRESET)
    for pool in (plain, hls):
        if Preset.MP3 in usable_presets(pool):
            return select_plan(pool, Preset.MP3)
    if usable_presets(formats):
        raise stream_not_playable()
    return select_plan(formats, PLAYBACK_PRESET)
