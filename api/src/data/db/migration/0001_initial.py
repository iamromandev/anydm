from typing import ClassVar

from tortoise import migrations
from tortoise.migrations import operations as ops


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = []

    initial: ClassVar[bool] = True

    operations: ClassVar[list[ops.Operation]] = []
