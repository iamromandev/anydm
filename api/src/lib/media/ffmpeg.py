"""ffmpeg invocation.

Argument construction is separated from execution so the arguments can be
tested without running anything. Both operations read local files, unlike the
Bun implementation which piped two remote URLs and had to keep them alive for
the length of the transcode.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from pathlib import Path

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.media.source import MediaInput, PlaylistCut, headers_args

_STDERR_TAIL = 2000

#: What a cut's local playlist may open: itself, its fragments over HTTP(S),
#: and the crypto protocol that decrypts AES-128 ones.
_CUT_PROTOCOLS = "file,http,https,tcp,tls,crypto"


def mux_args(ffmpeg: str, video: Path, audio: Path, destination: Path) -> list[str]:
    """Combine a video-only and an audio-only file without re-encoding either.

    The destination's extension picks the muxer, and the plan chose it to
    carry both codecs (``container_for``). Only MP4 has an index to move to the
    front, so only MP4 is asked to.
    """
    args = [
        ffmpeg,
        "-y",
        "-i", str(video),
        "-i", str(audio),
        "-c", "copy",
    ]
    if destination.suffix == ".mp4":
        args += ["-movflags", "+faststart"]
    return [*args, str(destination)]


def remux_args(ffmpeg: str, source: Path, destination: Path) -> list[str]:
    """Copy every stream of ``source`` into the container the destination names.

    For a part that came down as HLS. MPEG-TS is not MP4, and an fMP4 part is
    better with a normal index at the front. ffmpeg inserts ``aac_adtstoasc``
    itself when AAC moves from TS into MP4.
    """
    args = [
        ffmpeg,
        "-y",
        "-i", str(source),
        "-c", "copy",
    ]
    if destination.suffix == ".mp4":
        args += ["-movflags", "+faststart"]
    return [*args, str(destination)]


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
    inputs: Sequence[MediaInput] | Sequence[PlaylistCut],
    start_seconds: float,
    duration_seconds: float,
    destination: Path,
    *,
    has_video: bool,
    audio_track: int | None = None,
) -> list[str]:
    """One HLS-compatible segment, always re-encoded.

    ``inputs`` is one source, or a site's separate video and audio. With two,
    video comes from the first and audio from the second, and each gets its own
    ``-ss`` and headers: input options apply only to the ``-i`` they precede.

    An HLS source comes as ``PlaylistCut``s instead, never mixed with plain
    inputs: a local playlist of just the fragments the segment overlaps, read
    from their start. Seeking into HLS drops every stream's packets until a
    keyframe at or past the target, which clips TS audio and misreads fMP4
    (#87). Each cut is shifted by how much later its first fragment starts
    than the earliest cut's, which lines up two playlists whose fragments
    don't, and the output is trimmed to the segment. ffmpeg can't pass headers
    on from a local playlist, so the fragments go without the site's.

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

    ``audio_track`` picks one input's audio track, counted among its audio
    tracks (#99). Left to itself, ffmpeg takes the one with the most channels,
    so a 5.1 dub beats a stereo original. Two inputs never take one: a site's
    audio is its own input.
    """
    cuts = [source for source in inputs if isinstance(source, PlaylistCut)]
    plain = [source for source in inputs if isinstance(source, MediaInput)]
    if cuts and plain:
        raise ValueError("a segment reads plain inputs or playlist cuts, never both")
    args = [ffmpeg, "-y"]
    if cuts:
        origin = min(cut.starts_at for cut in cuts)
        for cut in cuts:
            args += [
                "-itsoffset", f"{cut.starts_at - origin:.3f}",
                "-protocol_whitelist", _CUT_PROTOCOLS,
                "-allowed_extensions", "ALL",
                "-i", str(cut.path),
            ]
        args += ["-ss", f"{start_seconds - origin:.3f}"]
    else:
        # The first segment reads from the start rather than seeking to it: a
        # seek there can only lose a first keyframe that decodes a moment
        # before zero, and the picture with it until the next one.
        seek = ["-ss", str(start_seconds)] if start_seconds > 0 else []
        for source in plain:
            args += [*seek, *headers_args(source.headers), "-i", source.url]
    args += ["-t", str(duration_seconds)]
    if len(inputs) > 1:
        args += ["-map", "0:v:0", "-map", "1:a:0"]
    elif audio_track is not None:
        args += [*(["-map", "0:v:0"] if has_video else []), "-map", f"0:a:{audio_track}"]
    if has_video:
        args += ["-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-ac", "2"]
    else:
        args += ["-vn", "-c:a", "aac", "-ac", "2"]
    args += ["-f", "mpegts", str(destination)]
    return args


def subtitle_args(
    ffmpeg: str,
    source: MediaInput,
    start_seconds: float,
    duration_seconds: float,
    outputs: Sequence[tuple[int, Path]],
) -> list[str]:
    """One segment's cues, as WebVTT, for each ``(track, destination)`` in ``outputs`` (#100).

    Not cut the way ``segment_args`` cuts video. With an input ``-ss`` and a
    ``-t``, #93 found cues landing 0.5 to 1 s late, by a different amount in each
    segment: subtitle packets keep an offset from wherever the demuxer landed.
    ``-copyts`` keeps the source's own times instead, so every cue is where
    the source puts it, and the end is absolute to match (``-to``). A cue that
    straddles a seam comes out in both segments, with the same times; the
    player drops the second.

    Every track comes out of one read of the input, since switching subtitles
    shouldn't cost another. Output options apply to the output that follows,
    so each carries its own map and end.
    """
    seek = ["-ss", str(start_seconds)] if start_seconds > 0 else []
    args = [ffmpeg, "-y", "-copyts", *seek, *headers_args(source.headers), "-i", source.url]
    for track, destination in outputs:
        args += [
            "-map", f"0:s:{track}",
            "-to", str(start_seconds + duration_seconds),
            "-c:s", "webvtt",
            "-f", "webvtt",
            str(destination),
        ]
    return args


def subtitle_file_args(ffmpeg: str, source: MediaInput, track: int, destination: Path) -> list[str]:
    """A whole subtitle track as WebVTT, for a file the browser plays itself (#100).

    ``-copyts`` for the same reason as ``subtitle_args``: an MKV with AAC starts
    at -0.023 s, the encoder's priming, and without it every cue would be
    re-zeroed on that and come out 23 ms late (#93).
    """
    return [
        ffmpeg,
        "-y",
        "-copyts",
        *headers_args(source.headers),
        "-i", source.url,
        "-map", f"0:s:{track}",
        "-c:s", "webvtt",
        "-f", "webvtt",
        str(destination),
    ]


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
