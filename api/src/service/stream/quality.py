"""What the player's quality menu offers, and what's playing (#103).

A site page offers each height it has, as ``format.py`` picks them, and
plays 1080p by default ("Auto"). A file or torrent plays as it is
("Original"), or scaled down to one of ``FILE_HEIGHTS`` below its own.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from src.lib.media.source import MediaInput

#: What a file can be scaled down to, when it's taller.
FILE_HEIGHTS = (1080, 720, 480)


@dataclass(frozen=True, slots=True)
class QualityState:
    #: The heights the menu offers besides the default, tallest first.
    heights: tuple[int, ...] = ()
    #: The one picked; ``None`` for the default.
    chosen: int | None = None
    #: The height actually playing, for the menu's label.
    playing: int | None = None
    #: What the default is: the site's 1080p pick, or the file as it is.
    default: Literal["auto", "original"] = "original"
    #: The default's own height: the site's pick, or the file's.
    default_height: int | None = None
    #: A site page's video format for each height: its id and input.
    site_video: dict[int, tuple[str, MediaInput]] = field(default_factory=dict)

    @property
    def scale_height(self) -> int | None:
        """The height ``segment_args`` scales a file to, if any. A site's formats come at their own."""
        return self.chosen if self.default == "original" else None

    def switched(self, height: int | None) -> QualityState:
        """The same menu with ``height`` picked; ``None`` goes back to the default."""
        return QualityState(
            heights=self.heights,
            chosen=height,
            playing=height if height is not None else self.default_height,
            default=self.default,
            default_height=self.default_height,
            site_video=self.site_video,
        )


def file_quality(source_height: int | None, has_video: bool, wanted: int | None = None) -> QualityState:
    """A file's menu: Original, and each of ``FILE_HEIGHTS`` below its own height."""
    if not has_video or not source_height:
        return QualityState()
    heights = tuple(height for height in FILE_HEIGHTS if height < source_height)
    chosen = wanted if wanted in heights else None
    return QualityState(
        heights=heights,
        chosen=chosen,
        playing=chosen or source_height,
        default="original",
        default_height=source_height,
    )
