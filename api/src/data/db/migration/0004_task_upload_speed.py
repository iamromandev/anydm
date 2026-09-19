"""``task.upload_speed_bps``.

A plain ``AddField``: the column carries no index, so the generated operation
has nothing to drop and no ``RunSQL`` is needed alongside it.
"""

from typing import ClassVar

from tortoise import fields, migrations
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0003_download')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.AddField(
            model_name='Task',
            name='upload_speed_bps',
            field=fields.BigIntField(default=0, description='Torrent-only. Spelled out rather than mirroring ``speed_bps``, which'),
        ),
    ]
