from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.catalog.provider import ProviderStatus
from src.data.type.catalog.source import SourceKind


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0004_config')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='catalog'),
        ops.CreateModel(
            name='Provider',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('base_url', fields.ForeignKeyField('model.Url', source_field='base_url_id', null=True, db_constraint=True, to_field='id', related_name='providers', on_delete=OnDelete.SET_NULL)),
                ('name', fields.CharField(max_length=64)),
                ('slug', fields.CharField(unique=True, max_length=64)),
                ('status', fields.CharEnumField(default=ProviderStatus.ACTIVE, description='ACTIVE: active\nINACTIVE: inactive', enum_type=ProviderStatus, max_length=8)),
                ('parser', fields.CharField(null=True, max_length=16)),
                ('api_key', fields.CharField(null=True, max_length=1024)),
            ],
            options={'table': 'provider', 'schema': 'catalog', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Provider'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Source',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('provider', fields.ForeignKeyField('model.Provider', source_field='provider_id', db_constraint=True, to_field='id', related_name='sources', on_delete=OnDelete.RESTRICT)),
                ('url', fields.ForeignKeyField('model.Url', source_field='url_id', db_constraint=True, to_field='id', related_name='sources', on_delete=OnDelete.RESTRICT)),
                ('kind', fields.CharEnumField(default=SourceKind.DIRECT, description='DIRECT: direct\nCONTENT: content\nTORRENT: torrent', enum_type=SourceKind, max_length=7)),
            ],
            options={'table': 'source', 'schema': 'catalog', 'app': 'model', 'unique_together': (('provider', 'url', 'kind'),), 'pk_attr': 'id', 'table_description': 'Source'},
            bases=['LinkBase'],
        ),
    ]
