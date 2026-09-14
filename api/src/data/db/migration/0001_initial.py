from typing import ClassVar

from tortoise import migrations
from tortoise.migrations.operations import Operation


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = []

    initial: ClassVar[bool] = True

    operations: ClassVar[list[Operation]] = []
