from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

#: Named rather than left to Postgres so the reverse can drop it by name.
_INFO_HASH_INDEX = "idx_task_info_hash"


class Migration(migrations.Migration):
    dependencies = [('model', '0002_task')]

    initial = False

    operations = [
        ops.AddField(
            model_name='Task',
            name='info_hash',
            field=fields.CharField(null=True, db_index=True, max_length=40),
        ),
        # ``db_default`` alongside ``default``: ``default`` only governs rows
        # the ORM creates from here on. Without a database-level default too,
        # adding a NOT NULL column to a table that already has rows fails with
        # "contains null values" the moment this runs against real data.
        ops.AddField(
            model_name='Task',
            name='uploaded_bytes',
            field=fields.BigIntField(default=0, db_default=0),
        ),
        ops.AddField(
            model_name='Task',
            name='peers_connected',
            field=fields.IntField(default=0, db_default=0),
        ),
        # Tortoise renders AddField without the index, every time, whatever
        # ``db_index`` says. Written by hand so the monitor's per-tick lookup by
        # info hash is not a sequential scan of every task ever created.
        # ``AddIndex`` is not the fix here: it wants a model-level Index and
        # reconciles against model state this migration has not changed.
        ops.RunSQL(
            f'CREATE INDEX IF NOT EXISTS "{_INFO_HASH_INDEX}" ON "task" ("info_hash")',
            reverse_sql=f'DROP INDEX IF EXISTS public."{_INFO_HASH_INDEX}"',
        ),
        ops.CreateModel(
            name='TorrentFile',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('task', fields.ForeignKeyField('model.Task', source_field='task_id', db_constraint=True, to_field='id', related_name='torrent_files', on_delete=OnDelete.CASCADE)),
                ('index', fields.IntField()),
                ('path', fields.CharField(max_length=1024)),
                ('size_bytes', fields.BigIntField(default=0)),
                ('selected', fields.BooleanField(default=True)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
            ],
            options={'table': 'torrent_file', 'app': 'model', 'unique_together': (('task', 'index'),), 'pk_attr': 'id', 'table_description': 'TorrentFile'},
            bases=['Base'],
        ),
    ]
