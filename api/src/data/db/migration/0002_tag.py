from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0001_initial')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.CreateModel(
            name='Tag',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('colour', fields.CharField(null=True, description='Any CSS colour the UI accepts, such as ``#d33``; null: the default.', max_length=16)),
            ],
            options={'table': 'tag', 'schema': 'shared', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Tag'},
            bases=['LinkBase'],
        ),
    ]
