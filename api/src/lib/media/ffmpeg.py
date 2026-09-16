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


def segment_args(
    ffmpeg: str,
    source: str,
    start_seconds: float,
    duration_seconds: float,
    destination: Path,
    *,
    has_video: bool,
) -> list[str]:
    """One HLS-compatible segment, always re-encoded.

    Always re-encoding (never ``-c copy``) is deliberate: it lets ``-ss`` cut
    at any exact timestamp cleanly, because ffmpeg decodes from the nearest
    prior keyframe internally. A copy segment would need the cut point to
    land exactly on a source keyframe, which arbitrary fixed-length
    boundaries essentially never do.

    ``-output_ts_offset`` matters just as much: each segment is its own
    independent ffmpeg process, so without it every segment's internal
    timestamps would restart near zero instead of continuing from where the
    previous segment left off. A player can only play the concatenated
    segments as one continuous stream if their timestamps actually are
    continuous — otherwise playback stalls the moment it crosses a segment
    boundary, even though every segment individually decodes fine.
    """
    args = [
        ffmpeg,
        "-y",
        "-ss", str(start_seconds),
        "-i", source,
        "-t", str(duration_seconds),
    ]
    if has_video:
        args += ["-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac"]
    else:
        args += ["-vn", "-c:a", "aac"]
    args += ["-output_ts_offset", str(start_seconds), "-f", "mpegts", str(destination)]
    return args


async def run(args: list[str]) -> None:
    """Run ffmpeg, raising an ``Error`` carrying its stderr tail on failure.

    Retryable, and governed by ``DOWNLOAD_MAX_ATTEMPTS`` like every other retryable
    failure: the common cause is a truncated input, which a re-download fixes.
    A missing binary is not retryable — no number of attempts installs ffmpeg.

    If the awaiting task is cancelled — a caller giving up on this encode,
    e.g. a stream session being torn down — the subprocess is killed rather
    than left to run orphaned. Cancelling the *task* does nothing to the
    *process* on its own: without this, an abandoned ffmpeg keeps writing to
    its output file indefinitely, which is exactly what a caller cleaning up
    that same file is trying to prevent.
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

    try:
        _, stderr = await process.communicate()
    except asyncio.CancelledError:
        process.kill()
        await process.wait()
        raise
    if process.returncode != 0:
        tail = (stderr or b"").decode(errors="replace")[-_STDERR_TAIL:]
        logger.error("ffmpeg|run(): exit {} — {}", process.returncode, tail)
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffmpeg exited with {process.returncode}: {tail}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
            retry_able=True,
        )
