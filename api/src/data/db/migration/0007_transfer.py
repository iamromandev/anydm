import uuid
from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.db_defaults import Now
from tortoise.indexes import Index
from tortoise.migrations import operations as ops

from src.data.type.transfer.attempt import AttemptStatus
from src.data.type.transfer.download import DownloadStatus
from src.data.type.transfer.media import MediaKind, Preset
from src.data.type.transfer.mirror import MirrorStatus
from src.data.type.transfer.segment import SegmentPart, SegmentStatus


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0006_torrent')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='transfer'),
        ops.CreateModel(
            name='Category',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('slug', fields.CharField(unique=True, max_length=64)),
                ('folder', fields.TextField(default='', unique=False)),
                ('position', fields.IntField(default=0)),
                ('builtin', fields.BooleanField(default=False)),
            ],
            options={'table': 'category', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Category'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Download',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('parent', fields.ForeignKeyField('model.Download', source_field='parent_id', null=True, description='The collection this download belongs to; none for a standalone download.', db_constraint=True, to_field='id', related_name='children', on_delete=OnDelete.CASCADE)),
                ('category', fields.ForeignKeyField('model.Category', source_field='category_id', db_index=True, db_constraint=True, to_field='id', db_default=uuid.UUID('00000000-0000-4000-8000-000000000001'), related_name='downloads', on_delete=OnDelete.RESTRICT)),
                ('folder', fields.TextField(null=True, description='Where its files are, relative to DOWNLOAD_DIR, once they have a place. Null on rows from before categories.', unique=False)),
                ('status', fields.CharEnumField(default=DownloadStatus.PENDING, db_index=True, description='PENDING: pending\nQUEUED: queued\nDOWNLOADING: downloading\nMUXING: muxing\nPAUSED: paused\nSEEDING: seeding\nCOMPLETED: completed\nFAILED: failed\nCANCELLED: cancelled', enum_type=DownloadStatus, max_length=11)),
                ('total_size', fields.BigIntField(null=True)),
                ('downloaded_size', fields.BigIntField(default=0)),
                ('uploaded_size', fields.BigIntField(default=0)),
                ('priority', fields.IntField(default=0)),
                ('speed_limit', fields.BigIntField(null=True)),
                ('started_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('completed_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('error', fields.TextField(null=True, unique=False)),
                ('error_code', fields.CharField(null=True, max_length=64)),
                ('attempts', fields.IntField(default=0)),
                ('next_attempt_at', fields.DatetimeField(null=True, description='When a retryable failure may run again; the row waits in ``PENDING`` until then.', auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'download', 'schema': 'transfer', 'app': 'model', 'indexes': [Index(fields=['status', 'created_at'], name='idx_download_status_created'), Index(fields=['parent_id', 'created_at'], name='idx_download_parent_created')], 'pk_attr': 'id', 'table_description': 'Download'},
            bases=['Base'],
        ),
        ops.CreateModel(
            name='File',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='files', on_delete=OnDelete.CASCADE)),
                ('filename', fields.CharField(max_length=512)),
                ('path', fields.TextField(unique=False)),
                ('size', fields.BigIntField(null=True)),
                ('index', fields.IntField()),
                ('downloaded_bytes', fields.BigIntField(default=0)),
                ('selected', fields.BooleanField(default=True)),
                ('mime_type', fields.CharField(null=True, max_length=128)),
            ],
            options={'table': 'file', 'schema': 'transfer', 'app': 'model', 'unique_together': (('download', 'index'),), 'pk_attr': 'id', 'table_description': 'File'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Media',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.OneToOneField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='media', on_delete=OnDelete.CASCADE)),
                ('title', fields.CharField(default='', description='The title the extract returned.', max_length=512)),
                ('kind', fields.CharEnumField(default=MediaKind.VIDEO, description='VIDEO: video\nAUDIO: audio\nFILE: file\nPLAYLIST: playlist\nCHANNEL: channel', enum_type=MediaKind, max_length=8)),
                ('preset', fields.CharEnumField(description='BEST: best\nP2160: 2160\nP1440: 1440\nP1080: 1080\nP720: 720\nP480: 480\nMP3: mp3', enum_type=Preset, max_length=8)),
                ('video_format', fields.CharField(null=True, max_length=64)),
                ('audio_format', fields.CharField(null=True, max_length=64)),
                ('playlist_index', fields.IntField(null=True, description='Its place in the playlist it was added from; none for a standalone video.')),
            ],
            options={'table': 'media', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Media'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Mirror',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('source', fields.ForeignKeyField('model.Source', source_field='source_id', db_constraint=True, to_field='id', related_name='mirrors', on_delete=OnDelete.CASCADE)),
                ('download', fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='mirrors', on_delete=OnDelete.CASCADE)),
                ('priority', fields.IntField(default=0)),
                ('status', fields.CharEnumField(default=MirrorStatus.AVAILABLE, description='AVAILABLE: available\nACTIVE: active\nFAILED: failed\nEXHAUSTED: exhausted\nDISABLED: disabled', enum_type=MirrorStatus, max_length=9)),
            ],
            options={'table': 'mirror', 'schema': 'transfer', 'app': 'model', 'unique_together': (('source', 'download'),), 'pk_attr': 'id', 'table_description': 'Mirror'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Attempt',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('mirror', fields.ForeignKeyField('model.Mirror', source_field='mirror_id', db_constraint=True, to_field='id', related_name='attempts', on_delete=OnDelete.CASCADE)),
                ('status', fields.CharEnumField(default=AttemptStatus.RUNNING, description='RUNNING: running\nCOMPLETED: completed\nFAILED: failed\nCANCELLED: cancelled', enum_type=AttemptStatus, max_length=9)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
                ('started_at', fields.DatetimeField(auto_now=False, auto_now_add=True)),
                ('completed_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'attempt', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Attempt'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Segment',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('file', fields.ForeignKeyField('model.File', source_field='file_id', db_constraint=True, to_field='id', related_name='segments', on_delete=OnDelete.CASCADE)),
                ('part', fields.CharEnumField(default=SegmentPart.FILE, description='FILE: file\nVIDEO: video\nAUDIO: audio', enum_type=SegmentPart, max_length=5)),
                ('status', fields.CharEnumField(default=SegmentStatus.PENDING, description='PENDING: pending\nDOWNLOADING: downloading\nCOMPLETED: completed\nFAILED: failed', enum_type=SegmentStatus, max_length=11)),
                ('start_byte', fields.BigIntField()),
                ('end_byte', fields.BigIntField()),
                ('downloaded_bytes', fields.BigIntField(default=0)),
            ],
            options={'table': 'segment', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Segment'},
            bases=['LinkBase'],
        ),
    ]
