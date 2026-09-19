"""TEMPORARY: proves a bad migration fails CI. Reverted in the next commit."""

from typing import ClassVar

from tortoise import migrations
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0004_task_upload_speed')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.RunSQL("ALTER TABLE task ADD COLUMN deliberately_broken NOT_A_REAL_TYPE"),
    ]
