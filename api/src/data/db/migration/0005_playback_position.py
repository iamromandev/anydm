"""Where each download was left in the player, so it resumes on any device (#96).

A new table only: the unique pair and the foreign key are created with it, so
there is no ``AddField`` whose ``db_index`` Tortoise would skip.
"""

from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0004_site')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.CreateModel(
            name='PlaybackPosition',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('task', fields.ForeignKeyField('model.Task', source_field='task_id', db_constraint=True, to_field='id', related_name='playback_positions', on_delete=OnDelete.CASCADE)),
                ('file_index', fields.IntField(default=0)),
                ('position_seconds', fields.FloatField(default=0.0)),
                ('duration_seconds', fields.FloatField(default=0.0)),
                ('watched', fields.BooleanField(default=False)),
            ],
            options={'table': 'playback_position', 'app': 'model', 'unique_together': (('task', 'file_index'),), 'pk_attr': 'id', 'table_description': 'PlaybackPosition'},
            bases=['LinkBase'],
        ),
    ]
