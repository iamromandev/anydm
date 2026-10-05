from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops

from src.data.type.download.download import PeerStatus, PieceStatus, TrackerStatus


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0005_catalog')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='torrent'),
        ops.CreateModel(
            name='Torrent',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('source', fields.ForeignKeyField('model.Source', source_field='source_id', db_constraint=True, to_field='id', related_name='torrents', on_delete=OnDelete.CASCADE)),
                ('name', fields.CharField(max_length=500)),
                ('info_hash', fields.CharField(unique=True, max_length=64)),
                ('total_bytes', fields.BigIntField(null=True)),
            ],
            options={'table': 'torrent', 'schema': 'torrent', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Torrent'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Peer',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('torrent', fields.ForeignKeyField('model.Torrent', source_field='torrent_id', db_constraint=True, to_field='id', related_name='peers', on_delete=OnDelete.CASCADE)),
                ('address', fields.CharField(max_length=255)),
                ('port', fields.IntField()),
                ('client', fields.CharField(null=True, max_length=255)),
                ('status', fields.CharEnumField(default=PeerStatus.CONNECTING, description='CONNECTING: connecting\nCONNECTED: connected\nDISCONNECTED: disconnected', enum_type=PeerStatus, max_length=12)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
                ('uploaded_bytes', fields.BigIntField(default=0)),
                ('last_seen_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'peer', 'schema': 'torrent', 'app': 'model', 'unique_together': (('torrent', 'address', 'port'),), 'pk_attr': 'id', 'table_description': 'Peer'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Piece',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('torrent', fields.ForeignKeyField('model.Torrent', source_field='torrent_id', db_constraint=True, to_field='id', related_name='pieces', on_delete=OnDelete.CASCADE)),
                ('index', fields.IntField()),
                ('size', fields.BigIntField()),
                ('hash', fields.CharField(max_length=64)),
                ('status', fields.CharEnumField(default=PieceStatus.PENDING, description='PENDING: pending\nDOWNLOADING: downloading\nCOMPLETED: completed\nFAILED: failed', enum_type=PieceStatus, max_length=11)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
            ],
            options={'table': 'piece', 'schema': 'torrent', 'app': 'model', 'unique_together': (('torrent', 'index'),), 'pk_attr': 'id', 'table_description': 'Piece'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='TorrentFile',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('torrent', fields.ForeignKeyField('model.Torrent', source_field='torrent_id', db_constraint=True, to_field='id', related_name='files', on_delete=OnDelete.CASCADE)),
                ('path', fields.TextField(unique=False)),
                ('size', fields.BigIntField()),
            ],
            options={'table': 'torrent_file', 'schema': 'torrent', 'app': 'model', 'pk_attr': 'id', 'table_description': 'TorrentFile'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Tracker',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('torrent', fields.ForeignKeyField('model.Torrent', source_field='torrent_id', db_constraint=True, to_field='id', related_name='trackers', on_delete=OnDelete.CASCADE)),
                ('url', fields.TextField(unique=False)),
                ('tier', fields.IntField(default=0)),
                ('status', fields.CharEnumField(default=TrackerStatus.ACTIVE, description='ACTIVE: active\nINACTIVE: inactive\nFAILED: failed', enum_type=TrackerStatus, max_length=8)),
            ],
            options={'table': 'tracker', 'schema': 'torrent', 'app': 'model', 'unique_together': (('torrent', 'url'),), 'pk_attr': 'id', 'table_description': 'Tracker'},
            bases=['LinkBase'],
        ),
    ]
