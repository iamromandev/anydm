"""Probe a source's duration and whether it carries a video stream.

Mirrors ``ffmpeg.py``'s shape (an args builder plus a runner), except the
runner here must capture stdout — ``ffmpeg.run()`` discards it.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from dataclasses import dataclass

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.media.audio import AudioTrack
from src.lib.media.source import headers_args

_STDERR_TAIL = 2000


@dataclass(frozen=True)
class ProbeResult:
    duration_seconds: float
    has_video: bool
    #: ffprobe's ``format_name``, e.g. ``"mov,mp4,m4a,3gp,3g2,mj2"``.
    container: str | None = None
    #: The first video stream's codec, and the audio track a player opens with.
    video_codec: str | None = None
    audio_codec: str | None = None
    #: Every audio track, in the order ``-map 0:a:N`` counts them (#99).
    audio_tracks: tuple[AudioTrack, ...] = ()


def _audio_track(index: int, stream: dict) -> AudioTrack:
    tags = stream.get("tags") or {}
    channels = stream.get("channels")
    return AudioTrack(
        index=index,
        language=tags.get("language") or None,
        title=tags.get("title") or None,
        channels=int(channels) if channels else None,
        codec=stream.get("codec_name") or None,
        default=bool((stream.get("disposition") or {}).get("default")),
    )


def probe_args(ffprobe: str, source: str, headers: Mapping[str, str] | None = None) -> list[str]:
    return [
        ffprobe,
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        *headers_args(headers),
        source,
    ]


async def capture(args: list[str], timeout_s: float | None = None) -> str:
    """Run ``args``, returning stdout as text. See ``ffmpeg.run`` for the sibling that discards it.

    A torrent-backed source can legitimately take a while to yield enough
    data for ffprobe to read the format, but a source with no peers never
    yields anything — without ``timeout_s`` that call hangs forever with no
    error, which looks identical to the app being broken.
    """
    try:
        process = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        logger.error("ffprobe|capture(): binary not found: {}", args[0])
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffprobe not found at {args[0]!r}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
        ) from exc

    try:
        stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout_s)
    except TimeoutError as exc:
        process.kill()
        await process.wait()
        logger.error("ffprobe|capture(): timed out after {}s", timeout_s)
        raise Error.create(
            code=Code.REQUEST_TIMEOUT,
            message=f"ffprobe did not finish within {timeout_s}s",
            error_type=ErrorType.TIMEOUT,
            retry_able=True,
        ) from exc
    if process.returncode != 0:
        tail = (stderr or b"").decode(errors="replace")[-_STDERR_TAIL:]
        logger.error("ffprobe|capture(): exit {} — {}", process.returncode, tail)
        raise Error.create(
            code=Code.BAD_GATEWAY,
            message=f"ffprobe exited with {process.returncode}: {tail}",
            error_type=ErrorType.EXTERNAL_API_ERROR,
            retry_able=False,
        )
    return stdout.decode(errors="replace")


def parse_probe_output(raw: str) -> ProbeResult:
    try:
        payload = json.loads(raw)
        duration = float(payload["format"]["duration"])
        streams = payload.get("streams", [])
        # Cover art is a one-picture video stream: an MP3 with a cover is still audio.
        videos = [
            s for s in streams
            if s.get("codec_type") == "video" and not (s.get("disposition") or {}).get("attached_pic")
        ]
        audios = [s for s in streams if s.get("codec_type") == "audio"]
        # The track a player opens with: the one flagged default, else the first.
        audio = next((s for s in audios if (s.get("disposition") or {}).get("default")), None)
        audio = audio or (audios[0] if audios else None)
        tracks = tuple(_audio_track(index, stream) for index, stream in enumerate(audios))
    except (KeyError, ValueError, TypeError, AttributeError, json.JSONDecodeError) as exc:
        raise Error.create(
            code=Code.BAD_GATEWAY,
            message="ffprobe returned an unreadable result",
            error_type=ErrorType.EXTERNAL_API_ERROR,
        ) from exc
    return ProbeResult(
        duration_seconds=duration,
        has_video=bool(videos),
        container=payload["format"].get("format_name"),
        video_codec=videos[0].get("codec_name") if videos else None,
        audio_codec=audio.get("codec_name") if audio else None,
        audio_tracks=tracks,
    )


async def probe(
    ffprobe: str,
    source: str,
    timeout_s: float | None = None,
    *,
    headers: Mapping[str, str] | None = None,
) -> ProbeResult:
    return parse_probe_output(await capture(probe_args(ffprobe, source, headers), timeout_s=timeout_s))
