from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.iam.actor.user import UserRole, UserStatus


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0002_shared')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='iam'),
        ops.CreateModel(
            name='User',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('role', fields.CharEnumField(default=UserRole.USER, description='ADMIN: admin\nUSER: user', enum_type=UserRole, max_length=5)),
                ('status', fields.CharEnumField(default=UserStatus.ACTIVE, description='ACTIVE: active\nINACTIVE: inactive', enum_type=UserStatus, max_length=8)),
                ('username', fields.CharField(unique=True, max_length=64)),
                ('display_name', fields.CharField(default='', max_length=128)),
                ('password_hash', fields.CharField(max_length=255)),
                ('locale', fields.CharField(null=True, max_length=35)),
                ('timezone', fields.CharField(null=True, max_length=64)),
                ('last_login_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'user', 'schema': 'iam', 'app': 'model', 'pk_attr': 'id', 'table_description': 'User'},
            bases=['Base'],
        ),
    ]
