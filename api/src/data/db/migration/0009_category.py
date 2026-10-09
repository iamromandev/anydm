"""Categories: a table seeded from the old Folder values, ``download.category_id`` and ``download.folder``.

Hand-edited after ``makemigrations``: the seed, the backfills, the index and the
view are not things the autodetector writes. ``list_item`` selects ``d.*``, so it
is dropped before the columns change and recreated after, now with ``category_id``.
"""

import os
from pathlib import Path
from typing import ClassVar
from uuid import uuid4

from dotenv import dotenv_values
from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.transfer.category import DOWNLOADS_ID, SEEDED_CATEGORIES
from src.lib.torrent.folder import torrent_folder

#: The view as migration 0007 left it: the old shape, dropped before the columns change.
_OLD_VIEW = """
CREATE VIEW transfer.list_item AS
WITH titled AS (
    SELECT d.*, m.kind AS media_kind,
           COALESCE(
               NULLIF(m.title, ''),
               (SELECT t.name FROM transfer.mirror mi
                  JOIN torrent.torrent t ON t.source_id = mi.source_id
                 WHERE mi.download_id = d.id ORDER BY mi.priority, mi.created_at LIMIT 1),
               (SELECT f.filename FROM transfer.file f WHERE f.download_id = d.id ORDER BY f.index LIMIT 1),
               ''
           ) AS title
    FROM transfer.download d
    LEFT JOIN transfer.media m ON m.download_id = d.id
    WHERE d.deleted_at IS NULL AND d.parent_id IS NULL
)
SELECT 'download'::text AS type, d.id, d.title, d.status::text AS status, d.created_at,
       d.total_size, d.downloaded_size,
       CASE WHEN COALESCE(d.total_size, 0) > 0
            THEN LEAST(100, (d.downloaded_size * 100 / d.total_size))::int ELSE 0 END AS progress
FROM titled d
WHERE d.media_kind IS NULL OR d.media_kind NOT IN ('playlist', 'channel')
UNION ALL
SELECT 'collection'::text AS type, c.id, c.title,
       CASE
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status IN ('pending', 'queued', 'downloading', 'muxing')) THEN 'downloading'
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status = 'paused') THEN 'paused'
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status = 'failed') THEN 'failed'
         ELSE 'completed'
       END AS status,
       c.created_at,
       (SELECT SUM(v.total_size) FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL)::bigint
         AS total_size,
       COALESCE((SELECT SUM(v.downloaded_size) FROM transfer.download v
                 WHERE v.parent_id = c.id AND v.deleted_at IS NULL), 0)::bigint AS downloaded_size,
       COALESCE((SELECT (COUNT(*) FILTER (WHERE v.status IN ('completed', 'seeding')) * 100 / NULLIF(COUNT(*), 0))
                 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL), 0)::int AS progress
FROM titled c
WHERE c.media_kind IN ('playlist', 'channel')
"""

#: The same view with ``category_id`` in both branches, at the same position.
_NEW_VIEW = """
CREATE VIEW transfer.list_item AS
WITH titled AS (
    SELECT d.*, m.kind AS media_kind,
           COALESCE(
               NULLIF(m.title, ''),
               (SELECT t.name FROM transfer.mirror mi
                  JOIN torrent.torrent t ON t.source_id = mi.source_id
                 WHERE mi.download_id = d.id ORDER BY mi.priority, mi.created_at LIMIT 1),
               (SELECT f.filename FROM transfer.file f WHERE f.download_id = d.id ORDER BY f.index LIMIT 1),
               ''
           ) AS title
    FROM transfer.download d
    LEFT JOIN transfer.media m ON m.download_id = d.id
    WHERE d.deleted_at IS NULL AND d.parent_id IS NULL
)
SELECT 'download'::text AS type, d.id, d.title, d.status::text AS status, d.created_at, d.category_id,
       d.total_size, d.downloaded_size,
       CASE WHEN COALESCE(d.total_size, 0) > 0
            THEN LEAST(100, (d.downloaded_size * 100 / d.total_size))::int ELSE 0 END AS progress
FROM titled d
WHERE d.media_kind IS NULL OR d.media_kind NOT IN ('playlist', 'channel')
UNION ALL
SELECT 'collection'::text AS type, c.id, c.title,
       CASE
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status IN ('pending', 'queued', 'downloading', 'muxing')) THEN 'downloading'
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status = 'paused') THEN 'paused'
         WHEN EXISTS (SELECT 1 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL
                      AND v.status = 'failed') THEN 'failed'
         ELSE 'completed'
       END AS status,
       c.created_at,
       c.category_id,
       (SELECT SUM(v.total_size) FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL)::bigint
         AS total_size,
       COALESCE((SELECT SUM(v.downloaded_size) FROM transfer.download v
                 WHERE v.parent_id = c.id AND v.deleted_at IS NULL), 0)::bigint AS downloaded_size,
       COALESCE((SELECT (COUNT(*) FILTER (WHERE v.status IN ('completed', 'seeding')) * 100 / NULLIF(COUNT(*), 0))
                 FROM transfer.download v WHERE v.parent_id = c.id AND v.deleted_at IS NULL), 0)::int AS progress
FROM titled c
WHERE c.media_kind IN ('playlist', 'channel')
"""

_SEED = (
    "INSERT INTO transfer.category (id, name, slug, folder, position, builtin, created_at, updated_at) VALUES "
    + ", ".join(
        f"('{DOWNLOADS_ID if slug == 'downloads' else uuid4()}', '{name}', '{slug}', '{folder}', {position}, "
        f"{'TRUE' if slug == 'downloads' else 'FALSE'}, now(), now())"
        for position, (name, slug, folder) in enumerate(SEEDED_CATEGORIES)
    )
)

def _old_torrent_prefix() -> str:
    """Where ``TORRENT_DIR`` pointed, relative to ``DOWNLOAD_DIR``; ``torrent`` when it can't be told."""
    found = {**dotenv_values(".env"), **os.environ}
    download = Path(found.get("DOWNLOAD_DIR") or "./download").resolve()
    torrent = Path(found.get("TORRENT_DIR") or "./download/torrent").resolve()
    try:
        return torrent.relative_to(download).as_posix()
    except ValueError:
        return "torrent"


async def _backfill_folders(apps, editor) -> None:
    """Record where existing files already are, so nothing moves.

    A finished standalone file is in ``<download_id>/``; a torrent in its old
    folder under ``TORRENT_DIR``. Collections stay ``NULL``: their folder is
    still derived from the collection's title and site id.
    """
    conn = editor.client
    await conn.execute_script(
        "UPDATE transfer.download d SET folder = d.id::text "
        "WHERE d.status = 'completed' AND d.parent_id IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM transfer.media m WHERE m.download_id = d.id "
        "                AND m.kind IN ('playlist', 'channel')) "
        "AND NOT EXISTS (SELECT 1 FROM transfer.mirror mi JOIN catalog.source s ON s.id = mi.source_id "
        "                WHERE mi.download_id = d.id AND s.kind = 'torrent')"
    )
    rows = await conn.execute_query_dict(
        "SELECT DISTINCT ON (d.id) d.id, t.name, t.info_hash FROM transfer.download d "
        "JOIN transfer.mirror mi ON mi.download_id = d.id "
        "JOIN torrent.torrent t ON t.source_id = mi.source_id "
        "ORDER BY d.id, mi.priority, mi.created_at"
    )
    prefix = Path(_old_torrent_prefix())
    for row in rows:
        folder = torrent_folder(prefix, row["name"], row["info_hash"]).as_posix()
        await conn.execute_query("UPDATE transfer.download SET folder = $1 WHERE id = $2", [folder, row["id"]])


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0008_play')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateModel(
            name='Category',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('slug', fields.CharField(unique=True, max_length=64)),
                ('folder', fields.TextField(default='')),
                ('position', fields.IntField(default=0)),
                ('builtin', fields.BooleanField(default=False)),
            ],
            options={'table': 'category', 'schema': 'transfer', 'app': 'model', 'ordering': ['position'], 'pk_attr': 'id', 'table_description': 'Category'},
            bases=['LinkBase'],
        ),
        ops.RunSQL(_SEED, reverse_sql="DELETE FROM transfer.category"),
        ops.RunSQL("DROP VIEW IF EXISTS transfer.list_item", reverse_sql=_OLD_VIEW),
        ops.AddField(
            model_name='Download',
            name='category',
            field=fields.ForeignKeyField('model.Category', source_field='category_id', db_index=True, db_constraint=True, to_field='id', related_name='downloads', on_delete=OnDelete.RESTRICT, db_default=DOWNLOADS_ID),
        ),
        # AddField never renders db_index, and the reverse of AlterField drops
        # the index unqualified, which fails outside the public schema. So the
        # index is written here, with a schema-qualified rollback.
        ops.RunSQL(
            'CREATE INDEX "idx_download_categor_040425" ON "transfer"."download" ("category_id");',
            reverse_sql='DROP INDEX "transfer"."idx_download_categor_040425";',
        ),
        ops.RemoveField(model_name='Download', name='folder'),
        ops.AddField(
            model_name='Download',
            name='folder',
            field=fields.TextField(null=True, description='Where its files are, relative to DOWNLOAD_DIR, once they have a place. Null on rows from before categories.'),
        ),
        ops.RunPython(_backfill_folders, reverse_code=ops.RunPython.noop),
        ops.RunSQL(_NEW_VIEW, reverse_sql="DROP VIEW IF EXISTS transfer.list_item"),
    ]
