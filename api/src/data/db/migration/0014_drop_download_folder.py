from pathlib import Path
from typing import ClassVar

from tortoise import migrations
from tortoise.migrations import operations as ops

from src.lib.folder import collection_dirname
from src.lib.identity import site_ref
from src.lib.torrent.folder import torrent_folder

# Where a download's files are is now derived from its category (and, for a torrent or a collection, from
# the torrent or the collection), so the folder each row stored is dropped.
#
# Forward only, and it refuses to run while a stored folder differs from the derived one: those downloads
# would lose track of their files, and a migration cannot move files. Move them first (a standalone
# download to its own category with the category mover), then migrate again.

_SELECT = """
SELECT d.id, d.status, d.folder, d.parent_id, cat.folder AS category_folder,
       t.name AS torrent_name, t.info_hash AS torrent_hash,
       md.title AS media_title, md.kind AS media_kind,
       u.value AS url, p.name AS provider, s.kind AS source_kind
FROM transfer.download d
JOIN transfer.category cat ON cat.id = d.category_id
LEFT JOIN torrent.torrent t ON t.id = d.torrent_id
LEFT JOIN transfer.media md ON md.download_id = d.id
LEFT JOIN LATERAL (
    SELECT m.source_id FROM transfer.mirror m WHERE m.download_id = d.id ORDER BY m.priority, m.created_at LIMIT 1
) first ON true
LEFT JOIN catalog.source s ON s.id = first.source_id
LEFT JOIN shared.url u ON u.id = s.url_id
LEFT JOIN catalog.provider p ON p.id = s.provider_id
WHERE d.deleted_at IS NULL
"""


def _posix(base: str, name: str) -> str:
    return (Path(base) / name).as_posix()


def _derived(row: dict, by_id: dict) -> str | None:
    """The folder the new code gives the row, from the same rules as ``folder_of``."""
    if row["parent_id"] is not None:
        parent = by_id.get(row["parent_id"])
        return _derived(parent, by_id) if parent is not None else None
    if row["media_kind"] in ("playlist", "channel"):
        provider = row["provider"] or ""
        ref = site_ref(provider, row["url"]) if row["url"] else ""
        return _posix(row["category_folder"], collection_dirname(row["media_title"] or "", ref))
    if row["source_kind"] == "torrent" and row["torrent_name"] is not None:
        return torrent_folder(Path(row["category_folder"]), row["torrent_name"], row["torrent_hash"]).as_posix()
    return row["category_folder"] if row["status"] == "completed" else None


async def _refuse_when_folders_differ(apps, schema_editor) -> None:
    rows = await schema_editor.client.execute_query_dict(_SELECT)
    by_id = {row["id"]: row for row in rows}
    differ = []
    for row in rows:
        stored, derived = row["folder"], _derived(row, by_id)
        # A download with no place yet (not finished, nothing stored) has nothing to lose.
        if (stored is None and derived is None) or (stored is not None and derived is None):
            continue
        if stored != derived:
            differ.append((row["id"], stored, derived))
    if differ:
        shown = "\n".join(f"  {id}: stored {stored!r}, derived {derived!r}" for id, stored, derived in differ[:20])
        raise RuntimeError(
            f"{len(differ)} download(s) are stored somewhere other than where their category puts them, "
            f"and would lose track of their files:\n{shown}\n"
            "Move each to its own category with the category mover (or move its files and set its folder to "
            "the derived one), then run the migration again."
        )


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0013_attempt_errors')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.RunPython(_refuse_when_folders_differ),
        ops.RemoveField(model_name='Download', name='folder'),
    ]
