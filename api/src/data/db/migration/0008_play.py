from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0007_transfer')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='play'),
        ops.CreateModel(
            name='PlaybackPosition',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('file', fields.ForeignKeyField('model.File', source_field='file_id', db_constraint=True, to_field='id', related_name='playback_positions', on_delete=OnDelete.CASCADE)),
                ('position_seconds', fields.FloatField(default=0.0)),
                ('duration_seconds', fields.FloatField(default=0.0)),
                ('watched', fields.BooleanField(default=False)),
            ],
            options={'table': 'playback_position', 'schema': 'play', 'app': 'model', 'unique_together': (('file',),), 'pk_attr': 'id', 'table_description': 'PlaybackPosition'},
            bases=['LinkBase'],
        ),
    ]
