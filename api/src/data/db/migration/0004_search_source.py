from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0003_download')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.CreateModel(
            name='SearchSource',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('enabled', fields.BooleanField(default=True)),
                ('base_url', fields.CharField(max_length=2048)),
            ],
            options={'table': 'search_source', 'app': 'model', 'pk_attr': 'id', 'table_description': 'SearchSource'},
            bases=['LinkBase'],
        ),
    ]
