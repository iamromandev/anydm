"""What anydm knows about one plugin."""

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from src.config.settings import Settings


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
