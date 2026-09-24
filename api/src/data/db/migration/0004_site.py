"""Tasks from any site: string format ids, the extractor, one ``site`` platform.

YouTube's itags are copied into string columns (a YouTube format id *is* its
itag), and every ``youtube`` row becomes a ``site`` row from the ``Youtube``
extractor, so queued and half-finished YouTube downloads resume on the one code
path.

New columns plus a copy, rather than a type change in place: Tortoise refuses to
alter a column's type ("automatic field type altering is not supported"), and
running the ``ALTER`` as raw SQL would leave its own migration state believing
the columns are still integers.
"""

from typing import ClassVar

from tortoise import fields, migrations
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation

from src.data.type.download.task import Platform

#: Kept as constants so the data test can run exactly what the migration runs.
COPY_ITAGS = (
    "UPDATE task SET video_format = video_itag::text, audio_format = audio_itag::text "
    "WHERE video_itag IS NOT NULL OR audio_itag IS NOT NULL"
)
RESTORE_ITAGS = (
    "UPDATE task SET video_itag = video_format::integer, audio_itag = audio_format::integer "
    "WHERE extractor = 'Youtube'"
)
MOVE_YOUTUBE_ROWS = "UPDATE task SET platform = 'site', extractor = 'Youtube' WHERE platform = 'youtube'"
RESTORE_YOUTUBE_ROWS = "UPDATE task SET platform = 'youtube' WHERE platform = 'site' AND extractor = 'Youtube'"


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0003_download')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.AddField(model_name='Task', name='video_format', field=fields.CharField(max_length=64, null=True)),
        ops.AddField(model_name='Task', name='audio_format', field=fields.CharField(max_length=64, null=True)),
        ops.AddField(
            model_name='Task',
            name='extractor',
            field=fields.CharField(null=True, description='yt-dlp\'s name for the site a ``site`` task came from: "Youtube", "Vimeo", ...', max_length=64),
        ),
        ops.RunSQL(sql=COPY_ITAGS, reverse_sql=RESTORE_ITAGS),
        ops.RunSQL(sql=MOVE_YOUTUBE_ROWS, reverse_sql=RESTORE_YOUTUBE_ROWS),
        ops.RemoveField(model_name='Task', name='video_itag'),
        ops.RemoveField(model_name='Task', name='audio_itag'),
        # Descriptions only: the enum lost ``youtube`` and gained ``site``, and
        # ``video_id`` now means any site's id.
        ops.AlterField(
            model_name='Task',
            name='platform',
            field=fields.CharEnumField(db_index=True, description='SITE: site\nDIRECT: direct\nTORRENT: torrent', enum_type=Platform, max_length=16),
        ),
        ops.AlterField(
            model_name='Task',
            name='video_id',
            field=fields.CharField(null=True, db_index=True, description="The site's own id for the media. Named for YouTube, which came first.", max_length=64),
        ),
    ]
