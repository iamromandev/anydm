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

#: Standalone downloads and collections as one list, with a collection's status
#: and totals computed from its downloads (the spec's amendment 6). Recreate it
#: in a later migration whenever a column it reads changes.
LIST_ITEM_VIEW = """
CREATE VIEW list_item AS
SELECT 'download'::text AS type, d.id, d.title, d.status::text AS status, d.created_at,
       d.total_bytes, d.downloaded_bytes,
       CASE WHEN COALESCE(d.total_bytes, 0) > 0
            THEN LEAST(100, (d.downloaded_bytes * 100 / d.total_bytes))::int ELSE 0 END AS progress
FROM download d
WHERE d.deleted_at IS NULL AND d.collection_id IS NULL
UNION ALL
SELECT 'collection'::text AS type, c.id, c.title,
       CASE
         WHEN EXISTS (SELECT 1 FROM download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status IN ('pending', 'downloading', 'muxing')) THEN 'downloading'
         WHEN EXISTS (SELECT 1 FROM download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status = 'paused') THEN 'paused'
         WHEN EXISTS (SELECT 1 FROM download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL
                      AND m.status = 'failed') THEN 'failed'
         ELSE 'complete'
       END AS status,
       c.created_at,
       (SELECT SUM(m.total_bytes) FROM download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL)::bigint
         AS total_bytes,
       COALESCE((SELECT SUM(m.downloaded_bytes) FROM download m
                 WHERE m.collection_id = c.id AND m.deleted_at IS NULL), 0)::bigint AS downloaded_bytes,
       COALESCE((SELECT (COUNT(*) FILTER (WHERE m.status IN ('complete', 'seeding')) * 100
                         / NULLIF(COUNT(*), 0))
                 FROM download m WHERE m.collection_id = c.id AND m.deleted_at IS NULL), 0)::int AS progress
FROM collection c
WHERE c.deleted_at IS NULL
"""


class Migration(migrations.Migration):
    initial: ClassVar[bool] = True

    operations: ClassVar[list[Operation]] = [
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
            options={'table': 'category', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Category'},
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
            options={'table': 'collection', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Collection'},
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
            options={'table': 'download_queue', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Queue'},
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
            options={'table': 'download', 'app': 'model', 'indexes': [Index(fields=['status', 'created_at'], name='idx_download_status_created'), Index(fields=['collection_id', 'position'], name='idx_download_collection_position'), Index(fields=['queue_id', 'status', 'queue_position'], name='idx_download_queue_order')], 'pk_attr': 'id', 'table_description': 'Download'},
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
            options={'table': 'download_file', 'app': 'model', 'unique_together': (('download', 'index'),), 'pk_attr': 'id', 'table_description': 'DownloadFile'},
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
            options={'table': 'mirror', 'app': 'model', 'unique_together': (('download', 'position'),), 'pk_attr': 'id', 'table_description': 'Mirror'},
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
            options={'table': 'playback_position', 'app': 'model', 'pk_attr': 'id', 'table_description': 'PlaybackPosition'},
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
            options={'table': 'segment', 'app': 'model', 'unique_together': (('download', 'part', 'index'),), 'pk_attr': 'id', 'table_description': 'Segment'},
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
            options={'table': 'site_detail', 'app': 'model', 'pk_attr': 'id', 'table_description': 'SiteDetail'},
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
            options={'table': 'search_source', 'app': 'model', 'pk_attr': 'id', 'table_description': 'Source'},
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
            options={'table': 'torrent_detail', 'app': 'model', 'pk_attr': 'id', 'table_description': 'TorrentDetail'},
            bases=['LinkBase'],
        ),
        # ``CreateModel`` applies neither partial uniques nor index methods
        # (src/core/base.py), so these are written out.
        ops.RunSQL(
            [
                "CREATE UNIQUE INDEX uniq_collection_listing ON collection (extractor, external_id) "
                "WHERE deleted_at IS NULL",
                # Hash, not btree: a magnet with many trackers outgrows btree's row limit,
                # and the duplicate check only ever asks for equality.
                "CREATE INDEX idx_download_source_url ON download USING hash (source_url)",
                LIST_ITEM_VIEW,
            ],
            reverse_sql=[
                "DROP VIEW IF EXISTS list_item",
                "DROP INDEX IF EXISTS idx_download_source_url",
                "DROP INDEX IF EXISTS uniq_collection_listing",
            ],
        ),
    ]
