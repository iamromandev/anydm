"""Give every built-in source a row, at the registry's constants, where it has none."""

from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow
from src.lib.sources.registry import BUILTINS


async def seed_missing_sources(repo: SearchSourceRepo) -> int:
    """Insert what is missing; never touch a row that exists. Returns how many were added."""
    return await repo.insert_missing([SearchSourceRow(b.name, b.default_enabled, b.default_url) for b in BUILTINS.values()])
