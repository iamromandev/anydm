"""Probe a source's duration and whether it carries a video stream.

Mirrors ``ffmpeg.py``'s shape (an args builder plus a runner), except the
runner here must capture stdout — ``ffmpeg.run()`` discards it.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType

_STDERR_TAIL = 2000


@dataclass(frozen=True)
class ProbeResult:
    duration_seconds: float
    has_video: bool


def probe_args(ffprobe: str, source: str) -> list[str]:
    return [
        ffprobe,
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        source,
    ]


async def capture(args: list[str]) -> str:
    """Run ``args``, returning stdout as text. See ``ffmpeg.run`` for the sibling that discards it."""
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

    stdout, stderr = await process.communicate()
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
        has_video = any(
            stream.get("codec_type") == "video" for stream in payload.get("streams", [])
        )
    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise Error.create(
            code=Code.BAD_GATEWAY,
            message="ffprobe returned an unreadable result",
            error_type=ErrorType.EXTERNAL_API_ERROR,
        ) from exc
    return ProbeResult(duration_seconds=duration, has_video=has_video)


async def probe(ffprobe: str, source: str) -> ProbeResult:
    return parse_probe_output(await capture(probe_args(ffprobe, source)))
