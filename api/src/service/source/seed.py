"""Give every built-in source a row, and once, bring in the indexers the .env configured."""

import os
from collections.abc import Mapping

from dotenv import dotenv_values
from loguru import logger

from src.data.repo.flag import AppFlagDatabaseRepo
from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow
from src.lib.sources.registry import BUILTINS
from src.lib.torznab.torznab import parse_indexers

#: The import runs a single time; afterwards this flag says it already happened.
IMPORT_FLAG = "search_indexers_imported"


async def seed_missing_sources(repo: SearchSourceRepo) -> int:
    """Insert what is missing; never touch a row that exists. Returns how many were added."""
    return await repo.insert_missing(
        [SearchSourceRow(b.name, b.name, b.default_enabled, b.default_url, None) for b in BUILTINS.values()]
    )


async def import_env_indexers(
    repo: SearchSourceRepo, flags: AppFlagDatabaseRepo, env: Mapping[str, str] | None = None
) -> int:
    """Insert the SEARCH_INDEXERS entries that have no row yet, once, with their keys.

    ``env`` is the merged environment (the real one when omitted: ``os.environ`` over ``api/.env``,
    because ``Settings`` reads the file itself and never populates ``os.environ``).
    A set flag is a no-op even with the variable present, so a deleted source never comes back.
    A missing or blank variable leaves the flag unset: a later boot still tries.
    A malformed value is logged and the flag is still written: a broken string must not retry forever.
    """
    if await flags.is_set(IMPORT_FLAG):
        return 0
    merged = dict(dotenv_values(".env")) | dict(os.environ) if env is None else dict(env)
    urls = (merged.get("SEARCH_INDEXERS") or "").strip()
    if not urls:
        return 0
    try:
        indexers = parse_indexers(urls, merged.get("SEARCH_INDEXER_KEYS") or "")
    except ValueError as error:
        logger.warning(f"seed|ignoring malformed SEARCH_INDEXERS: {error}")
        await flags.mark(IMPORT_FLAG)
        return 0
    added = await repo.insert_missing(
        [SearchSourceRow(i.name, "torznab", True, i.url, i.key) for i in indexers]
    )
    await flags.mark(IMPORT_FLAG)
    return added
