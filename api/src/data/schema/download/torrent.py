from __future__ import annotations

from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema


class FileSchema(BaseSchema):
    """One file inside a torrent, as the API reports it."""

    index: int
    path: str
    size_bytes: int = 0
    selected: bool = True
    downloaded_bytes: int = 0


class TorrentResolveRequest(BaseSchema):
    torrent: Annotated[
        str,
        Field(
            min_length=1,
            description="A magnet link, an http URL to a .torrent, or a base64 .torrent file",
        ),
    ]


class TorrentResolveResponse(BaseSchema):
    """What a magnet turns out to contain. No task exists yet."""

    info_hash: str
    title: str = ""
    total_bytes: int = 0
    files: list[FileSchema] = Field(default_factory=list)


class TorrentDownloadRequest(BaseSchema):
    torrent: Annotated[
        str,
        Field(
            min_length=1,
            description="A magnet link, an http URL to a .torrent, or a base64 .torrent file",
        ),
    ]
    files: Annotated[
        list[Annotated[int, Field(ge=0)]],
        Field(
            default_factory=list,
            description="File indexes to download. Empty means every file.",
        ),
    ]
