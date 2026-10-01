"""Streaming. Names resolve on first use, so importing one module here
(``torrent_source``, say) doesn't load the whole stream service."""

from importlib import import_module
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from .reaper import TorrentReaper as TorrentReaper
    from .session import SegmentState as SegmentState
    from .session import StreamSession as StreamSession
    from .session import StreamSessionStore as StreamSessionStore
    from .stream_service import StreamService as StreamService
    from .sweeper import StreamIdleSweeper as StreamIdleSweeper

_WHERE = {
    "TorrentReaper": ".reaper",
    "SegmentState": ".session",
    "StreamSession": ".session",
    "StreamSessionStore": ".session",
    "StreamService": ".stream_service",
    "StreamIdleSweeper": ".sweeper",
}


def __getattr__(name: str) -> Any:
    if name not in _WHERE:
        raise AttributeError(f"module 'src.service.stream' has no attribute {name!r}")
    return getattr(import_module(_WHERE[name], __name__), name)
