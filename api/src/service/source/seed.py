"""Give every built-in source a row, at the registry's constants, where it has none."""

from src.data.repo.search.interface.source import SourceRepo, SourceRow
from src.lib.sources.registry import BUILTINS


async def seed_missing_sources(repo: SourceRepo) -> int:
    """Insert what is missing; never touch a row that exists. Returns how many were added."""
    return await repo.seed(
        [SourceRow(b.name, b.name, b.default_enabled, b.default_url, None) for b in BUILTINS.values()]
    )
