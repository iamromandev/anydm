from typing import ClassVar
from uuid import uuid4

from orjson import loads
from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.data import JSON_DUMPS
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.constraints import UniqueConstraint

from src.data.type.download.download import AttemptStatus, DownloadStatus, Folder, MirrorStatus, Preset, SegmentStatus


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0006_torrent')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.CreateModel(
            name='Download',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('folder', fields.CharEnumField(default=Folder.DOWNLOADS, description='DOWNLOADS: downloads\nVIDEOS: videos\nMOVIES: movies\nTV_SHOWS: tv_shows\nMUSIC: music\nAUDIOBOOKS: audiobooks\nPODCASTS: podcasts\nDOCUMENTS: documents\nEBOOKS: ebooks\nIMAGES: images\nPHOTOS: photos\nSOFTWARE: software\nGAMES: games\nARCHIVES: archives\nOTHER: other', enum_type=Folder, max_length=10)),
                ('status', fields.CharEnumField(default=DownloadStatus.PENDING, description='PENDING: pending\nQUEUED: queued\nDOWNLOADING: downloading\nPAUSED: paused\nCOMPLETED: completed\nFAILED: failed\nCANCELLED: cancelled', enum_type=DownloadStatus, max_length=11)),
                ('total_size', fields.BigIntField(null=True)),
                ('downloaded_size', fields.BigIntField(default=0)),
                ('uploaded_size', fields.BigIntField(default=0)),
                ('priority', fields.IntField(default=0)),
                ('speed_limit', fields.BigIntField(null=True)),
                ('started_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('completed_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'download', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Download'},
            bases=['LinkBase'],
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
            ],
            options={'table': 'file', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'File'},
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
            options={'table': 'queue', 'schema': 'transfer', 'app': 'model', 'constraints': [UniqueConstraint(fields=('is_default',), name='uidx_queue_default', condition='"is_default" IS TRUE')], 'pk_attr': 'id', 'table_description': 'Queue'},
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
            name='SiteDetail',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.OneToOneField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='site_detail', on_delete=OnDelete.CASCADE)),
                ('preset', fields.CharEnumField(description='BEST: best\nP2160: 2160\nP1440: 1440\nP1080: 1080\nP720: 720\nP480: 480\nMP3: mp3', enum_type=Preset, max_length=8)),
                ('video_format', fields.CharField(null=True, max_length=64)),
                ('audio_format', fields.CharField(null=True, max_length=64)),
            ],
            options={'table': 'site_detail', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'SiteDetail'},
            bases=['LinkBase'],
        ),
        ops.AddConstraint(model_name='Queue', constraint=UniqueConstraint(fields=('is_default',), name='uidx_queue_default', condition='"is_default" IS TRUE')),
    ]
