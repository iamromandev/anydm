"""What happens to the downloaded parts once the bytes are on disk.

A Protocol rather than a function so the worker never learns how a file is
assembled, and so the tests can substitute a spy for the ffmpeg subprocess.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any, Protocol

from src.core.error import Error
from src.data.type import Kind
from src.lib import media

Runner = Callable[[list[str]], Awaitable[None]]


class PostProcessor(Protocol):
    async def run(
        self, task: Any, parts: dict[str, Path], destination: Path, *, fragmented: frozenset[str] = frozenset()
    ) -> None:
        """Turn ``parts`` into the single file at ``destination``.

        ``fragmented`` names the parts yt-dlp's downloader fetched, which
        arrive in a container of their own (MPEG-TS for HLS).
        """
        ...


class FfmpegPostProcessor(PostProcessor):
    """Turns the downloaded parts into the finished file.

    Three cases, in the order they are checked. An MP3 task is transcoded even
    though it has only one part, because the stream YouTube serves is AAC or
    Opus. Any other single part is already the file and is renamed into place,
    which covers combined streams and every direct download. The exception is
    a part yt-dlp's downloader fetched, which is remuxed out of the container
    it arrived in. Two parts are muxed.
    """

    def __init__(self, ffmpeg: str, runner: Runner = media.run) -> None:
        self._ffmpeg = ffmpeg
        self._run = runner

    async def run(
        self, task: Any, parts: dict[str, Path], destination: Path, *, fragmented: frozenset[str] = frozenset()
    ) -> None:
        if not parts:
            raise Error.internal(message="Nothing was downloaded")

        if task.kind == Kind.AUDIO:
            audio = parts.get("audio")
            if audio is None:
                raise Error.internal(message="Audio task has no audio part")
            await self._run(media.mp3_args(self._ffmpeg, audio, destination))
            audio.unlink(missing_ok=True)
            return

        if len(parts) == 1:
            name, part = next(iter(parts.items()))
            if name in fragmented:
                await self._run(media.remux_args(self._ffmpeg, part, destination))
                part.unlink(missing_ok=True)
            else:
                part.replace(destination)
            return

        video, audio = parts.get("video"), parts.get("audio")
        if video is not None and audio is not None:
            await self._run(media.mux_args(self._ffmpeg, video, audio, destination))
            video.unlink(missing_ok=True)
            audio.unlink(missing_ok=True)
            return

        raise Error.internal(message=f"Cannot combine parts: {sorted(parts)}")
