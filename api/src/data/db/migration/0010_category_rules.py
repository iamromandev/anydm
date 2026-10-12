import json
from typing import ClassVar

from orjson import loads
from tortoise import fields, migrations
from tortoise.fields.data import JSON_DUMPS
from tortoise.migrations import operations as ops

from src.data.type.catalog.source import SourceKind

# The rules a seeded category files a download by, as they stood when this ran. Copied here, not
# imported, so changing the defaults later does not change what this migration did.
_RULES = {
    'videos': (['.mkv', '.mp4', '.avi', '.mov', '.webm', '.m4v', '.wmv', '.flv'], ['video/']),
    'music': (['.mp3', '.flac', '.m4a', '.aac', '.ogg', '.opus', '.wav'], ['audio/']),
    'documents': (['.pdf', '.doc', '.docx', '.txt', '.rtf', '.odt', '.xls', '.xlsx', '.ppt', '.pptx'], []),
    'ebooks': (['.epub', '.mobi', '.azw3'], []),
    'images': (['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg', '.bmp'], ['image/']),
    'software': (['.iso', '.exe', '.msi', '.dmg', '.pkg', '.deb', '.rpm', '.apk'], []),
    'archives': (['.zip', '.rar', '.7z', '.tar', '.gz', '.xz', '.bz2'], []),
}

_BACKFILL = [
    f"UPDATE transfer.category SET extensions = '{json.dumps(extensions)}'::jsonb, "
    f"mime_prefixes = '{json.dumps(prefixes)}'::jsonb WHERE slug = '{slug}'"
    for slug, (extensions, prefixes) in _RULES.items()
]


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0009_audit')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.AddField(
            model_name='Category',
            name='extensions',
            field=fields.JSONField(default=list, description='What puts a download here when nobody picked a category: lowercase, with the dot (``.mkv``).', db_default=[], encoder=JSON_DUMPS, decoder=loads),
        ),
        ops.AddField(
            model_name='Category',
            name='mime_prefixes',
            field=fields.JSONField(default=list, description='Content-Type prefixes that do the same (``video/``); an extension is tried first.', db_default=[], encoder=JSON_DUMPS, decoder=loads),
        ),
        ops.AlterField(
            model_name='Source',
            name='kind',
            field=fields.CharEnumField(default=SourceKind.DIRECT, description='DIRECT: direct\nSITE: site\nTORRENT: torrent', enum_type=SourceKind, max_length=7),
        ),
        # Seed never touches a row that exists, so an install that already has these categories gets its rules here.
        ops.RunSQL(_BACKFILL, reverse_sql=ops.RunSQL.noop),
    ]
