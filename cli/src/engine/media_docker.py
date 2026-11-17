"""The media engine lives in src.plugin.engine now; this name stays so older imports (and patches) still find it."""

import sys
from typing import TYPE_CHECKING

from src.plugin import engine as _engine

if TYPE_CHECKING:
    from src.plugin.engine import *  # noqa: F403

sys.modules[__name__] = _engine
