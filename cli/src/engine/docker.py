"""The runner lives in src.plugin.runner now; this name stays so older imports (and patches) still find it."""

import sys
from typing import TYPE_CHECKING

from src.plugin import runner as _runner

if TYPE_CHECKING:
    from src.plugin.runner import *  # noqa: F403
    from src.plugin.runner import _clock as _clock  # a star import skips private names; tests move this clock

sys.modules[__name__] = _runner  # the same module object, so patching src.engine.docker.X patches the runner
