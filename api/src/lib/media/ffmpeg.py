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

    Each segment's own internal timestamps are left alone — no attempt is
    made to offset them to their "true" position in the full stream. Every
    segment is its own independent ffmpeg process with its own encoder
    buffering delay, so two segments' raw timestamps never line up *exactly*
    at the seam even when offset; MSE demuxers reject that as an out-of-order
    buffer. ``playlist_text()`` marks every segment after the first with
    ``#EXT-X-DISCONTINUITY`` instead, which is what tells a player to stop
    expecting the raw timestamps to be continuous and remap each segment to
    its playlist-declared position — the standard HLS mechanism for exactly
    this situation (also used for ad breaks and stream splicing).

    Audio is always downmixed to stereo (``-ac 2``). A multichannel source
    (5.1 is common in movie rips) re-encoded to multichannel AAC reliably
    fails to append into Chromium's MediaSource — confirmed live against a
    real 5.1 torrent, where hls.js's fragmented-MP4 remux of an unmodified
    6-channel AAC segment raised CHUNK_DEMUXER_ERROR_APPEND_FAILED on every
    attempt. Stereo is the safe, universally-supported target.
    """
    args = [
        ffmpeg,
        "-y",
        "-ss", str(start_seconds),
        "-i", source,
        "-t", str(duration_seconds),
    ]
    if has_video:
        args += ["-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-ac", "2"]
    else:
        args += ["-vn", "-c:a", "aac", "-ac", "2"]
    args += ["-f", "mpegts", str(destination)]
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
