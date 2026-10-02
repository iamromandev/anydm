"""The services, and the ``get_*`` factories that wire them, from ``src.service.wiring``.

Resolved on first use rather than at import: importing one service module (say,
``src.service.download.live``) runs this package first, and it must not drag
every repository and engine in with it.
"""

from importlib import import_module
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from src.service.wiring import *  # noqa: F403


def __getattr__(name: str) -> Any:
    # ``import_module``, not ``from src.service import wiring``: the latter looks
    # ``wiring`` up on this package first, which lands back here.
    wiring = import_module("src.service.wiring")
    try:
        return getattr(wiring, name)
    except AttributeError:
        raise AttributeError(f"module 'src.service' has no attribute {name!r}") from None
