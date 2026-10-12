from typing import ClassVar

from tortoise import migrations
from tortoise.migrations import operations as ops

# A size is `..._size` on Download, and now on Torrent and Attempt too.


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0011_torrent_catalog')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.RenameField(model_name='Torrent', old_name='total_bytes', new_name='total_size'),
        ops.RenameField(model_name='Attempt', old_name='downloaded_bytes', new_name='downloaded_size'),
    ]
