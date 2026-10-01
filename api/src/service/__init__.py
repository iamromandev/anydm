"""The services, and the ``get_*`` factories that wire them, from ``src.service.wiring``.

Resolved on first use rather than at import: importing one service module (say,
``src.service.download.live``) runs this package first, and it must not drag
every repository and engine in with it.
"""

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from src.service.wiring import *  # noqa: F403


def __getattr__(name: str) -> Any:
    from src.service import wiring

    try:
        return getattr(wiring, name)
    except AttributeError:
        raise AttributeError(f"module 'src.service' has no attribute {name!r}") from None
