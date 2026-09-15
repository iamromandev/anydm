from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.indexes import Index
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation

from src.data.type.download.task import Kind, Platform, Preset, TaskStatus


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0001_initial')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.CreateModel(
            name='Task',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('source_url', fields.TextField(unique=False)),
                ('platform', fields.CharEnumField(db_index=True, description='YOUTUBE: youtube\nDIRECT: direct\nTORRENT: torrent', enum_type=Platform, max_length=16)),
                ('video_id', fields.CharField(null=True, db_index=True, max_length=64)),
                ('info_hash', fields.CharField(null=True, db_index=True, description="The torrent's info hash, and the only torrent identifier stored. rqbit", max_length=40)),
                ('preset', fields.CharEnumField(description='BEST: best\nP2160: 2160\nP1440: 1440\nP1080: 1080\nP720: 720\nP480: 480\nMP3: mp3', enum_type=Preset, max_length=8)),
                ('kind', fields.CharEnumField(description='VIDEO: video\nAUDIO: audio\nFILE: file\nTORRENT: torrent', enum_type=Kind, max_length=8)),
                ('title', fields.CharField(default='', max_length=512)),
                ('filename', fields.CharField(default='', max_length=512)),
                ('mime_type', fields.CharField(null=True, max_length=128)),
                ('video_itag', fields.IntField(null=True)),
                ('audio_itag', fields.IntField(null=True)),
                ('status', fields.CharEnumField(db_index=True, description='PENDING: pending\nDOWNLOADING: downloading\nMUXING: muxing\nPAUSED: paused\nSEEDING: seeding\nCOMPLETE: complete\nFAILED: failed\nCANCELED: canceled', enum_type=TaskStatus, max_length=16)),
                ('progress', fields.IntField(default=0)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
                ('total_bytes', fields.BigIntField(null=True)),
                ('speed_bps', fields.BigIntField(default=0)),
                ('eta_seconds', fields.IntField(null=True)),
                ('uploaded_bytes', fields.BigIntField(default=0, description='Torrent-only. Stored rather than computed so the share ratio the card')),
                ('peers_connected', fields.IntField(default=0, description='Torrent-only. Stored so a reconnecting browser sees a peer count at once')),
                ('file_path', fields.CharField(null=True, max_length=1024)),
                ('file_size', fields.BigIntField(null=True)),
                ('error', fields.TextField(null=True, unique=False)),
                ('error_code', fields.CharField(null=True, max_length=64)),
                ('attempts', fields.IntField(default=0)),
                ('next_attempt_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('started_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('completed_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('heartbeat_at', fields.DatetimeField(null=True, description='Written by the progress flush. Startup recovery does not consult it —', auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'task', 'app': 'model', 'indexes': [Index(fields=['status', 'created_at'], name='idx_task_status_created')], 'pk_attr': 'id', 'table_description': 'Task'},
            bases=['Base'],
        ),
    ]
