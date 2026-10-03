from typing import ClassVar
from uuid import uuid4

from orjson import loads
from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.fields.data import JSON_DUMPS
from tortoise.fields.db_defaults import Now
from tortoise.indexes import Index
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation

from src.data.type.download.download import (
    ChecksumAlgo,
    CollectionKind,
    DownloadStatus,
    MediaKind,
    Platform,
    Preset,
    SegmentPart,
)
from src.data.type.iam.actor.user import UserRole
from src.data.type.iam.membership.share import ShareResource, ShareRole
from src.data.type.iam.runtime.session import SessionKind

#: Standalone downloads and collections as one list, with a collection's status
#: and totals computed from its downloads (the spec's amendment 6). Recreate it
#: in a later migration whenever a column it reads changes.
LIST_ITEM_VIEW = """
CREATE VIEW transfer.list_item AS
SELECT 'download'::text AS type, d.id, d.title, d.status::text AS status, d.created_at,
       d.total_bytes, d.downloaded_bytes,
       CASE WHEN COALESCE(d.total_bytes, 0) > 0
            THEN LEAST(100, (d.downloaded_bytes * 100 / d.total_bytes))::int ELSE 0 END AS progress
FROM transfer.download d
WHERE d.deleted_at IS NULL AND d.collection_id IS NULL
UNION ALL
SELECT 'collection'::text AS type, c.id, c.title,
       CASE
         WHEN EXISTS (SELECT 1 FROM transfer.download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status IN ('pending', 'downloading', 'muxing')) THEN 'downloading'
         WHEN EXISTS (SELECT 1 FROM transfer.download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status = 'paused') THEN 'paused'
         WHEN EXISTS (SELECT 1 FROM transfer.download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status = 'failed') THEN 'failed'
         ELSE 'complete'
       END AS status,
       c.created_at,
       (SELECT SUM(m.total_bytes) FROM transfer.download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL)::bigint
         AS total_bytes,
       COALESCE((SELECT SUM(m.downloaded_bytes) FROM transfer.download m
                 WHERE m.collection_id = c.id AND m.deleted_at IS NULL), 0)::bigint AS downloaded_bytes,
       COALESCE((SELECT (COUNT(*) FILTER (WHERE m.status IN ('complete', 'seeding')) * 100
                         / NULLIF(COUNT(*), 0))
                 FROM transfer.download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL), 0)::int AS progress
FROM organize.collection c
WHERE c.deleted_at IS NULL
"""

class Migration(migrations.Migration):
    initial: ClassVar[bool] = True

    operations: ClassVar[list[Operation]] = [
        ops.CreateSchema(schema_name='iam'),
        ops.CreateSchema(schema_name='organize'),
        ops.CreateSchema(schema_name='shared'),
        ops.CreateSchema(schema_name='transfer'),
        ops.CreateModel(
            name='Category',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('save_dir', fields.CharField(description='Relative to ``DOWNLOAD_DIR``.', max_length=1024)),
                ('extensions', fields.JSONField(default=list, description='Lower-case, without the dot. An extension belongs to at most one category.', encoder=JSON_DUMPS, decoder=loads)),
                ('position', fields.IntField(default=0)),
            ],
            options={'table': 'category', 'schema': 'shared', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Category'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Collection',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('kind', fields.CharEnumField(description='PLAYLIST: playlist\nCHANNEL: channel', enum_type=CollectionKind, max_length=16)),
                ('source_url', fields.TextField(unique=False)),
                ('extractor', fields.CharField(description='The listing\'s extractor: "YoutubeTab", not its videos\' "Youtube".', max_length=64)),
                ('external_id', fields.CharField(max_length=128)),
                ('title', fields.CharField(default='', max_length=512)),
                ('folder', fields.CharField(description='Relative to ``DOWNLOAD_DIR``; its videos finish into it.', max_length=1024)),
                ('preset', fields.CharEnumField(description='BEST: best\nP2160: 2160\nP1440: 1440\nP1080: 1080\nP720: 720\nP480: 480\nMP3: mp3', enum_type=Preset, max_length=8)),
            ],
            options={'table': 'collection', 'schema': 'organize', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Collection'},
            bases=['Base'],
        ),
        ops.CreateModel(
            name='Queue',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('max_concurrent', fields.IntField(default=1)),
                ('start_time', fields.TimeField(null=True, description='Both null: always open. ``stop_time`` before ``start_time`` crosses midnight.', auto_now=False, auto_now_add=False)),
                ('stop_time', fields.TimeField(null=True, auto_now=False, auto_now_add=False)),
                ('days', fields.JSONField(null=True, description='ISO weekdays, 1 (Monday) to 7; null means every day.', encoder=JSON_DUMPS, decoder=loads)),
                ('position', fields.IntField(default=0)),
            ],
            options={'table': 'queue', 'schema': 'organize', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Queue'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Download',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('deleted_at', fields.DatetimeField(null=True, db_index=True, auto_now=False, auto_now_add=False)),
                ('source_url', fields.TextField(unique=False)),
                ('platform', fields.CharEnumField(db_index=True, description='SITE: site\nDIRECT: direct\nTORRENT: torrent', enum_type=Platform, max_length=16)),
                ('media_kind', fields.CharEnumField(description='VIDEO: video\nAUDIO: audio\nFILE: file', enum_type=MediaKind, max_length=8)),
                ('title', fields.CharField(default='', max_length=512)),
                ('status', fields.CharEnumField(db_index=True, description='PENDING: pending\nDOWNLOADING: downloading\nMUXING: muxing\nPAUSED: paused\nSEEDING: seeding\nCOMPLETE: complete\nFAILED: failed\nCANCELED: canceled', enum_type=DownloadStatus, max_length=16)),
                ('category', fields.ForeignKeyField('model.Category', source_field='category_id', null=True, db_constraint=True, to_field='id', related_name='downloads', on_delete=OnDelete.SET_NULL)),
                ('collection', fields.ForeignKeyField('model.Collection', source_field='collection_id', null=True, db_constraint=True, to_field='id', related_name='downloads', on_delete=OnDelete.CASCADE)),
                ('position', fields.IntField(null=True, description="Its number in the collection's listing.")),
                ('save_dir', fields.CharField(null=True, description="Overrides the category's folder; relative to ``DOWNLOAD_DIR``.", max_length=1024)),
                ('folder', fields.CharField(null=True, description='The resolved folder, relative to ``DOWNLOAD_DIR``, fixed when it starts.', max_length=1024)),
                ('queue', fields.ForeignKeyField('model.Queue', source_field='queue_id', db_constraint=True, to_field='id', related_name='downloads', on_delete=OnDelete.RESTRICT)),
                ('queue_position', fields.BigIntField(default=0, description='Lower runs first, within its queue.')),
                ('start_at', fields.DatetimeField(null=True, description='Not before this; separate from ``next_attempt_at`` so a retry never erases a schedule.', auto_now=False, auto_now_add=False)),
                ('download_limit_bps', fields.BigIntField(null=True, description="Null: the global limit alone. HTTP downloads only; rqbit can't cap one torrent.")),
                ('checksum_algo', fields.CharEnumField(null=True, description='SHA256: sha256\nSHA1: sha1\nMD5: md5', enum_type=ChecksumAlgo, max_length=8)),
                ('checksum_expected', fields.CharField(null=True, max_length=128)),
                ('checksum_ok', fields.BooleanField(null=True, description='Null until checked.')),
                ('total_bytes', fields.BigIntField(null=True)),
                ('downloaded_bytes', fields.BigIntField(default=0)),
                ('error', fields.TextField(null=True, unique=False)),
                ('error_code', fields.CharField(null=True, max_length=64)),
                ('attempts', fields.IntField(default=0)),
                ('next_attempt_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('started_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('completed_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'download', 'schema': 'transfer', 'app': 'model', 'indexes': [Index(fields=['status', 'created_at'], name='idx_download_status_created'), Index(fields=['collection_id', 'position'], name='idx_download_collection_position'), Index(fields=['queue_id', 'status', 'queue_position'], name='idx_download_queue_order')], 'pk_attr': 'id', 'table_description': 'Download'},
            bases=['Base'],
        ),
        ops.CreateModel(
            name='DownloadFile',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='download_files', on_delete=OnDelete.CASCADE)),
                ('index', fields.IntField()),
                ('path', fields.CharField(max_length=1024)),
                ('size_bytes', fields.BigIntField(default=0)),
                ('downloaded_bytes', fields.BigIntField(default=0, description='Bytes on disk for this file.')),
                ('selected', fields.BooleanField(default=True)),
                ('mime_type', fields.CharField(null=True, max_length=128)),
            ],
            options={'table': 'download_file', 'schema': 'transfer', 'app': 'model', 'unique_together': (('download', 'index'),), 'pk_attr': 'id', 'table_description': 'DownloadFile'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Mirror',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='mirrors', on_delete=OnDelete.CASCADE)),
                ('url', fields.TextField(unique=False)),
                ('position', fields.IntField()),
                ('last_error', fields.TextField(null=True, description='Why this address failed the last time it was tried.', unique=False)),
            ],
            options={'table': 'mirror', 'schema': 'transfer', 'app': 'model', 'unique_together': (('download', 'position'),), 'pk_attr': 'id', 'table_description': 'Mirror'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='PlaybackPosition',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('file', fields.OneToOneField('model.DownloadFile', source_field='file_id', db_constraint=True, to_field='id', related_name='playback', on_delete=OnDelete.CASCADE)),
                ('position_seconds', fields.FloatField(default=0.0)),
                ('duration_seconds', fields.FloatField(default=0.0)),
                ('watched', fields.BooleanField(default=False, description='Played to within its last seconds, at least once.')),
            ],
            options={'table': 'playback_position', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'PlaybackPosition'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Segment',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='segments', on_delete=OnDelete.CASCADE)),
                ('part', fields.CharEnumField(description='FILE: file\nVIDEO: video\nAUDIO: audio', enum_type=SegmentPart, max_length=8)),
                ('index', fields.IntField()),
                ('start_byte', fields.BigIntField()),
                ('end_byte', fields.BigIntField()),
                ('downloaded', fields.BigIntField(default=0, description='Bytes durably written, counted from ``start_byte``. Never ahead of disk.')),
            ],
            options={'table': 'segment', 'schema': 'transfer', 'app': 'model', 'unique_together': (('download', 'part', 'index'),), 'pk_attr': 'id', 'table_description': 'Segment'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='SiteDetail',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.OneToOneField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='site_detail', on_delete=OnDelete.CASCADE)),
                ('extractor', fields.CharField(description='yt-dlp\'s name for the site: "Youtube", "Vimeo", ...', max_length=64)),
                ('video_id', fields.CharField(db_index=True, description='The site\'s own id for the video; what the picker\'s "already have it" asks about.', max_length=128)),
                ('preset', fields.CharEnumField(description='BEST: best\nP2160: 2160\nP1440: 1440\nP1080: 1080\nP720: 720\nP480: 480\nMP3: mp3', enum_type=Preset, max_length=8)),
                ('video_format', fields.CharField(null=True, description="The site's format ids, chosen when the download is planned. A YouTube itag, as a string.", max_length=64)),
                ('audio_format', fields.CharField(null=True, max_length=64)),
            ],
            options={'table': 'site_detail', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'SiteDetail'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Source',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=64)),
                ('kind', fields.CharField(max_length=16)),
                ('enabled', fields.BooleanField(default=True)),
                ('base_url', fields.CharField(max_length=2048)),
                ('api_key', fields.CharField(null=True, max_length=1024)),
            ],
            options={'table': 'source', 'schema': 'shared', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Source'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='TorrentDetail',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('download', fields.OneToOneField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='torrent_detail', on_delete=OnDelete.CASCADE)),
                ('info_hash', fields.CharField(unique=True, max_length=40)),
                ('uploaded_bytes', fields.BigIntField(default=0, description='Stored so the share ratio needs no second source.')),
            ],
            options={'table': 'torrent_detail', 'schema': 'transfer', 'app': 'model', 'pk_attr': 'id', 'table_description': 'TorrentDetail'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='User',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('username', fields.CharField(unique=True, max_length=64)),
                ('display_name', fields.CharField(default='', max_length=128)),
                ('password_hash', fields.CharField(description='Never returned by the API and never logged.', max_length=255)),
                ('role', fields.CharEnumField(default=UserRole.USER, description='ADMIN: admin\nUSER: user', enum_type=UserRole, max_length=8)),
                ('is_active', fields.BooleanField(default=True)),
                ('folder', fields.CharField(unique=True, max_length=64)),
                ('last_login_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
            ],
            options={'table': 'user', 'schema': 'iam', 'app': 'model', 'pk_attr': 'id', 'table_description': 'User'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Session',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('user', fields.ForeignKeyField('model.User', source_field='user_id', db_constraint=True, to_field='id', related_name='sessions', on_delete=OnDelete.CASCADE)),
                ('kind', fields.CharEnumField(description='SESSION: session\nAPI: api', enum_type=SessionKind, max_length=8)),
                ('name', fields.CharField(null=True, description='What the person called an API token; null for a browser login.', max_length=64)),
                ('token_hash', fields.CharField(unique=True, max_length=64)),
                ('expires_at', fields.DatetimeField(null=True, description='Null: an API token that does not expire.', auto_now=False, auto_now_add=False)),
                ('last_used_at', fields.DatetimeField(null=True, auto_now=False, auto_now_add=False)),
                ('user_agent', fields.CharField(default='', max_length=255)),
            ],
            options={'table': 'session', 'schema': 'iam', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Session'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='Share',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('resource_type', fields.CharEnumField(description='DOWNLOAD: download\nCOLLECTION: collection', enum_type=ShareResource, max_length=16)),
                ('resource_id', fields.UUIDField()),
                ('user', fields.ForeignKeyField('model.User', source_field='user_id', null=True, db_constraint=True, to_field='id', related_name='shares', on_delete=OnDelete.CASCADE)),
                ('role', fields.CharEnumField(description='VIEW: view\nMANAGE: manage', enum_type=ShareRole, max_length=8)),
                ('created_by', fields.ForeignKeyField('model.User', source_field='created_by_id', description='The owner or an admin. RESTRICT: a user who granted shares is not deleted by accident.', db_constraint=True, to_field='id', related_name='granted_shares', on_delete=OnDelete.RESTRICT)),
            ],
            options={'table': 'share', 'schema': 'iam', 'app': 'model', 'unique_together': (('resource_type', 'resource_id', 'user'),), 'indexes': [Index(fields=['user_id', 'resource_type'], name='idx_share_user_type'), Index(fields=['resource_type', 'resource_id'], name='idx_share_resource')], 'pk_attr': 'id', 'table_description': 'Share'},
            bases=['LinkBase'],
        ),
        ops.CreateModel(
            name='UserSetting',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('user', fields.ForeignKeyField('model.User', source_field='user_id', db_constraint=True, to_field='id', related_name='user_settings', on_delete=OnDelete.CASCADE)),
                ('key', fields.CharField(max_length=64)),
                ('value', fields.JSONField(encoder=JSON_DUMPS, decoder=loads)),
            ],
            options={'table': 'user_setting', 'schema': 'iam', 'app': 'model', 'unique_together': (('user', 'key'),), 'pk_attr': 'id', 'table_description': 'UserSetting'},
            bases=['LinkBase'],
        ),
        # ``CreateModel`` applies neither partial uniques nor index methods
        # (src/core/base.py), so these are written out.
        ops.RunSQL(
            [
                "CREATE UNIQUE INDEX uniq_collection_listing ON organize.collection (extractor, external_id) "
                "WHERE deleted_at IS NULL",
                # Hash, not btree: a magnet with many trackers outgrows btree's row limit,
                # and the duplicate check only ever asks for equality.
                "CREATE INDEX idx_download_source_url ON transfer.download USING hash (source_url)",
                # A NULL user means "everyone"; unique_together never sees two NULLs as equal.
                "CREATE UNIQUE INDEX uniq_share_everyone ON iam.share (resource_type, resource_id) "
                "WHERE user_id IS NULL",
                LIST_ITEM_VIEW,
            ],
            reverse_sql=[
                "DROP VIEW IF EXISTS transfer.list_item",
                "DROP INDEX IF EXISTS iam.uniq_share_everyone",
                "DROP INDEX IF EXISTS transfer.idx_download_source_url",
                "DROP INDEX IF EXISTS organize.uniq_collection_listing",
            ],
        ),
    ]
