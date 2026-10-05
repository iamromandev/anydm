from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.indexes import Index
from tortoise.migrations import operations as ops

from src.data.type.shared.ref import RefType


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0001_initial')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='shared'),
        ops.CreateModel(
            name='Tag',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(max_length=64)),
                ('slug', fields.CharField(max_length=64)),
                ('ref_type', fields.CharEnumField(default=RefType.DOWNLOAD, description='DOWNLOAD: download', enum_type=RefType, max_length=8)),
                ('ref_id', fields.UUIDField(db_index=True)),
            ],
            options={'table': 'tag', 'schema': 'shared', 'app': 'model', 'unique_together': (('ref_type', 'ref_id', 'slug'),), 'indexes': [Index(fields=['ref_type', 'slug'], name='idx_tag_ref_type_slug')], 'pk_attr': 'id', 'table_description': 'Tag'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Url',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('value', fields.TextField(unique=False)),
                ('normalized', fields.TextField(unique=False)),
                ('normalized_hash', fields.CharField(unique=True, max_length=64)),
                ('scheme', fields.CharField(max_length=16)),
                ('host', fields.CharField(null=True, max_length=255)),
                ('port', fields.SmallIntField(null=True)),
                ('path', fields.TextField(null=True, unique=False)),
                ('query', fields.TextField(null=True, unique=False)),
                ('fragment', fields.TextField(null=True, unique=False)),
            ],
            options={'table': 'url', 'schema': 'shared', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Url'},
            bases=['LinkBase'],
        ),
    ]
