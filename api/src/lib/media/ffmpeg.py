"""ffmpeg invocation.

Argument construction is separated from execution so the arguments can be
tested without running anything. Both operations read local files, unlike the
Bun implementation which piped two remote URLs and had to keep them alive for
the length of the transcode.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType

_STDERR_TAIL = 2000


def mux_args(ffmpeg: str, video: Path, audio: Path, destination: Path) -> list[str]:
    """Combine a video-only and an audio-only file without re-encoding either."""
    return [
        ffmpeg,
        "-y",
        "-i", str(video),
        "-i", str(audio),
        "-c", "copy",
        "-movflags", "+faststart",
        str(destination),
    ]


def mp3_args(ffmpeg: str, audio: Path, destination: Path) -> list[str]:
    """Transcode an audio stream to MP3 at V2 (roughly 190 kbps VBR)."""
    return [
        ffmpeg,
        "-y",
        "-i", str(audio),
        "-vn",
        "-c:a", "libmp3lame",
        "-q:a", "2",
        str(destination),
    ]


async def run(args: list[str]) -> None:
    """Run ffmpeg, raising an ``Error`` carrying its stderr tail on failure.

    Retryable, and governed by ``MAX_ATTEMPTS`` like every other retryable
    failure: the common cause is a truncated input, which a re-download fixes.
    A missing binary is not retryable — no number of attempts installs ffmpeg.
    """
    try:
        process = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        logger.error("ffmpeg|run(): binary not found: {}", args[0])
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffmpeg not found at {args[0]!r}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
        ) from exc

    _, stderr = await process.communicate()
    if process.returncode != 0:
        tail = (stderr or b"").decode(errors="replace")[-_STDERR_TAIL:]
        logger.error("ffmpeg|run(): exit {} — {}", process.returncode, tail)
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffmpeg exited with {process.returncode}: {tail}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
            retry_able=True,
        )
