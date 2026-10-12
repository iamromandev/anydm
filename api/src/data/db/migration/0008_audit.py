from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.catalog.source import SourceKind


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0007_play')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='audit'),
        ops.CreateModel(
            name='Speed',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('hour', fields.DatetimeField(unique=True, auto_now=False, auto_now_add=False)),
                ('total_bps', fields.BigIntField(default=0)),
                ('maximum_bps', fields.BigIntField(default=0)),
                ('samples', fields.IntField(default=0)),
            ],
            options={'table': 'speed', 'schema': 'audit', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Speed'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Traffic',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('day', fields.DateField(db_index=True)),
                ('kind', fields.CharEnumField(description='DIRECT: direct\nSITE: site\nTORRENT: torrent', enum_type=SourceKind, max_length=7)),
                ('provider', fields.ForeignKeyField('model.Provider', source_field='provider_id', null=True, db_constraint=True, to_field='id', related_name='traffics', on_delete=OnDelete.SET_NULL)),
                ('downloaded_size', fields.BigIntField(default=0)),
                ('uploaded_size', fields.BigIntField(default=0)),
                ('finished', fields.IntField(default=0)),
                ('failed', fields.IntField(default=0)),
                ('removed', fields.IntField(default=0)),
            ],
            options={'table': 'traffic', 'schema': 'audit', 'app': 'model', 'unique_together': (('day', 'kind', 'provider'),), 'pk_attr': 'id', 'table_description': 'Traffic'},
            bases=['LinkBase'],
        ),
    ]
