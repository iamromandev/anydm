"""What anydm knows about one plugin."""

import re
from dataclasses import dataclass
from typing import TYPE_CHECKING

from src.core.errors import UsageError

if TYPE_CHECKING:
    from src.config.settings import Settings


# What an installed plugin may run: `<reference>@sha256:<64 hex>`, or a bare image id `sha256:<64 hex>` (a local-only
# image). A tag alone can be moved to other content, so it is never accepted.
_PINNED = re.compile(r"(?:[A-Za-z0-9][A-Za-z0-9._/:-]*@)?sha256:[0-9a-f]{64}")


def is_pinned(image: str) -> bool:
    return _PINNED.fullmatch(image) is not None


def missing_capability(name: str, need: str) -> UsageError:
    """The error for a plugin asked for something it does not do (`need` is "download" or "info"); one wording and
    one exit code (2) wherever it comes from."""
    return UsageError(f"plugin {name} cannot {'show info' if need == 'info' else need}")


@dataclass(frozen=True)
class Plugin:
    name: str
    image: str
    version: str
    capabilities: frozenset[str]
    permissions: frozenset[str]
    builtin: bool = False


def media_image_plugin(image: str) -> Plugin:
    """The built-in media plugin on `image`: its permissions are always approved and it is not pinned by digest."""
    return Plugin("media", image, "builtin", frozenset({"check", "download", "info"}),
                  frozenset({"network", "cookies"}), builtin=True)


def media_plugin(settings: Settings) -> Plugin:
    return media_image_plugin(settings.media_image)
