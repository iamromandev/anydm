"""A playlist added as one group (v0.5 part 2).

``parent`` and ``position`` on ``task``, and ``playlist`` in ``kind``. Tortoise's
``AddField`` skips ``db_index``, so both indexes are created by hand.
"""

from typing import ClassVar

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation

from src.data.type.download.task import Kind

CREATE_PARENT_INDEX = "CREATE INDEX IF NOT EXISTS idx_task_parent ON task (parent_id)"
DROP_PARENT_INDEX = "DROP INDEX IF EXISTS idx_task_parent"
CREATE_POSITION_INDEX = "CREATE INDEX IF NOT EXISTS idx_task_parent_position ON task (parent_id, position)"
DROP_POSITION_INDEX = "DROP INDEX IF EXISTS idx_task_parent_position"


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0005_playback_position')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.AddField(
            model_name='Task',
            name='parent',
            field=fields.ForeignKeyField(
                'model.Task',
                source_field='parent_id',
                db_constraint=True,
                to_field='id',
                related_name='playlist_entries',
                null=True,
                on_delete=OnDelete.CASCADE,
            ),
        ),
        ops.AddField(model_name='Task', name='position', field=fields.IntField(null=True)),
        ops.RunSQL(sql=CREATE_PARENT_INDEX, reverse_sql=DROP_PARENT_INDEX),
        ops.RunSQL(sql=CREATE_POSITION_INDEX, reverse_sql=DROP_POSITION_INDEX),
        ops.AlterField(
            model_name='Task',
            name='kind',
            field=fields.CharEnumField(
                description='VIDEO: video\nAUDIO: audio\nFILE: file\nTORRENT: torrent\nPLAYLIST: playlist',
                enum_type=Kind,
                max_length=8,
            ),
        ),
    ]
