from typing import ClassVar
from uuid import uuid4

from orjson import loads
from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.data import JSON_DUMPS
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.config.preference import PreferenceKey


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0003_iam')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='config'),
        ops.CreateModel(
            name='Preference',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('user', fields.ForeignKeyField('model.User', source_field='user_id', db_constraint=True, to_field='id', related_name='preferences', on_delete=OnDelete.CASCADE)),
                ('key', fields.CharEnumField(description='DEFAULT_PRESET: default_preset\nCONFIRM_BEFORE_REMOVE: confirm_before_remove\nAUDIO_LANGUAGE: audio_language\nSUBTITLE_LANGUAGE: subtitle_language\nTHEME: theme\nSORT: sort', enum_type=PreferenceKey, max_length=32)),
                ('value', fields.JSONField(encoder=JSON_DUMPS, decoder=loads)),
            ],
            options={'table': 'preference', 'schema': 'config', 'app': 'model', 'unique_together': (('user', 'key'),), 'pk_attr': 'id', 'table_description': 'Preference'},
            bases=['LinkBase'],
        ),
    ]
