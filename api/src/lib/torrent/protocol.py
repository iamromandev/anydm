"""The torrent engine's vocabulary, as plain data.

Nothing here imports httpx or knows rqbit exists. ``client.py`` is the only
module that does, and it returns these types — which is what lets everything
above this line be tested without a network or a second process.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Protocol

from src.lib.torrent.source import TorrentSource


@dataclass(frozen=True, slots=True)
class FileInfo:
    """One file inside a torrent.

    ``index`` is the file's position in the torrent, which is what rqbit's
    ``only_files`` selection and its ``file_progress`` array are both keyed by.
    """

    index: int
    path: str
    size_bytes: int
    included: bool = True


@dataclass(frozen=True, slots=True)
class TorrentDetails:
    info_hash: str
    name: str
    output_folder: str
    files: list[FileInfo] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class TorrentProgress:
    """One engine-side sample, already converted into this project's units.

    Speeds are bytes per second here. rqbit reports mebibytes per second in a
    field called ``mbps``; ``mapping.py`` is where that is undone.
    """

    info_hash: str
    #: ``initializing`` | ``live`` | ``paused`` | ``error``
    state: str
    finished: bool
    progress_bytes: int
    uploaded_bytes: int
    total_bytes: int
    download_bps: int
    upload_bps: int
    peers_connected: int
    eta_seconds: int | None = None
    error: str | None = None
    #: Bytes downloaded per file, indexed the same way ``FileInfo.index`` is.
    file_progress: list[int] = field(default_factory=list)


class TorrentClient(Protocol):
    """What the service layer is allowed to ask of a torrent engine."""

    async def ping(self) -> bool:
        """True when the engine answers. Never raises."""
        ...

    async def resolve(self, source: TorrentSource) -> TorrentDetails:
        """Metadata and the file list, without starting a download."""
        ...

    async def add(
        self,
        source: TorrentSource,
        *,
        only_files: Sequence[int],
        output_folder: str,
    ) -> TorrentDetails:
        """Start downloading the selected files into ``output_folder``."""
        ...

    async def list_progress(self) -> list[TorrentProgress]:
        """One sample per torrent the engine holds."""
        ...

    async def pause(self, info_hash: str) -> None: ...

    async def start(self, info_hash: str) -> None: ...

    async def delete(self, info_hash: str) -> None:
        """Forget the torrent and remove its files."""
        ...

    async def forget(self, info_hash: str) -> None:
        """Forget the torrent, leaving its files on disk."""
        ...
