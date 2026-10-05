"""What a file is, guessed from its name.

Done where file rows are written, so every row records a type the same way
whichever kind of download it belongs to. Nothing here reads a file: a name the
standard library does not know is ``None``, which is honest, not a guess.
"""

from __future__ import annotations

import mimetypes
from pathlib import PurePosixPath


def mime_of(path: str) -> str | None:
    """The media type of the file ``path`` names, judged by its file name alone."""
    media_type, _ = mimetypes.guess_type(PurePosixPath(path).name)
    return media_type
