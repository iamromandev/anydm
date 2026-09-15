from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0002_task')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.CreateModel(
            name='File',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('task', fields.ForeignKeyField('model.Task', source_field='task_id', db_constraint=True, to_field='id', related_name='torrent_files', on_delete=OnDelete.CASCADE)),
                ('index', fields.IntField()),
                ('path', fields.CharField(max_length=1024)),
                ('size_bytes', fields.BigIntField(default=0)),
                ('selected', fields.BooleanField(default=True)),
                ('downloaded_bytes', fields.BigIntField(default=0, description='Bytes on disk for this file, mirrored from the engine each tick.')),
            ],
            options={'table': 'file', 'app': 'model', 'unique_together': (('task', 'index'),), 'pk_attr': 'id', 'table_description': 'File'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Segment',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('task', fields.ForeignKeyField('model.Task', source_field='task_id', db_constraint=True, to_field='id', related_name='segments', on_delete=OnDelete.CASCADE)),
                ('part', fields.CharField(max_length=16)),
                ('index', fields.IntField()),
                ('start_byte', fields.BigIntField()),
                ('end_byte', fields.BigIntField()),
                ('downloaded', fields.BigIntField(default=0, description='Bytes durably written, counted from ``start_byte``. Never ahead of disk.')),
            ],
            options={'table': 'segment', 'app': 'model', 'unique_together': (('task', 'part', 'index'),), 'pk_attr': 'id', 'table_description': 'Segment'},
            bases=['LinkBase'],
        ),
    ]
