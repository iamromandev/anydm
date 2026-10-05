from typing import ClassVar
from uuid import uuid4

from orjson import loads
from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.data import JSON_DUMPS
from tortoise.fields.db_defaults import Now
from tortoise.indexes import Index
from tortoise.migrations import operations as ops
from tortoise.migrations.constraints import UniqueConstraint

from src.data.type.download.download import (
    AttemptStatus,
    DownloadStatus,
    Folder,
    MediaKind,
    MirrorStatus,
    Preset,
    SegmentStatus,
)


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0006_torrent')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateSchema(schema_name='transfer'),
        ops.CreateModel(
            name='Download',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('parent', fields.ForeignKeyField('model.Download', source_field='parent_id', null=True, description='The collection this download belongs to; none for a standalone download.', db_constraint=True, to_field='id', related_name='children', on_delete=OnDelete.CASCADE)),
                ('folder', fields.CharEnumField(default=Folder.DOWNLOADS, description='DOWNLOADS: downloads\nVIDEOS: videos\nMOVIES: movies\nTV_SHOWS: tv_shows\nMUSIC: music\nAUDIOBOOKS: audiobooks\nPODCASTS: podcasts\nDOCUMENTS: documents\nEBOOKS: ebooks\nIMAGES: images\nPHOTOS: photos\nSOFTWARE: software\nGAMES: games\nARCHIVES: archives\nOTHER: other', enum_type=Folder, max_length=10)),
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
                ('status', fields.CharEnumField(default=SegmentStatus.PENDING, description='PENDING: pending\nDOWNLOADING: downloading\nCOMPLETED: completed\nFAILED: failed', enum_type=SegmentStatus, max_length=11)),
                ('start_byte', fields.BigIntField()),
                ('end_byte', fields.BigIntField()),
                ('downloaded_bytes', fields.BigIntField(default=0)),
            ],
            options={'table': 'segment', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Segment'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Queue',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('slug', fields.CharField(unique=True, description='The name made URL- and comparison-safe; what code and the API refer to.', max_length=64)),
                ('is_default', fields.BooleanField(default=False, description='The queue a new download joins when none is named.')),
                ('is_paused', fields.BooleanField(default=False, description='Stops the whole queue without touching its downloads.')),
                ('max_concurrent', fields.IntField(default=1)),
                ('start_time', fields.TimeField(null=True, description='Both null: always open. ``stop_time`` before ``start_time`` crosses midnight.', auto_now=False, auto_now_add=False)),
                ('stop_time', fields.TimeField(null=True, auto_now=False, auto_now_add=False)),
                ('days', fields.JSONField(null=True, description='ISO weekdays, 1 (Monday) to 7; null means every day.', encoder=JSON_DUMPS, decoder=loads)),
                ('position', fields.IntField(default=0)),
            ],
            options={'table': 'queue', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Queue'},
            bases=['LinkBase'],
        ),
        # CreateModel renders no Meta.constraints (src/core/base.py), so the partial unique is added here.
        ops.AddConstraint(
            model_name='Queue',
            constraint=UniqueConstraint(fields=('is_default',), name='uniq_queue_default', condition='is_default'),
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
    ]
