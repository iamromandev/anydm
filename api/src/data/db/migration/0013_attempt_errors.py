from typing import ClassVar

from tortoise import fields, migrations
from tortoise.migrations import operations as ops

# Why a download failed belongs to the try that failed, not to the download: Attempt gets error and
# error_code, Download loses them. A download's current error is its newest try's, shown while it is
# failed or waiting to retry.
#
# Forward only: the current error of each download is carried onto its newest try, or onto a new
# failed try on its primary mirror when it has none, and the columns are dropped.

_BACKFILL = [
    # A download that has tries: the error goes onto the newest.
    'UPDATE transfer.attempt a SET error = d.error, error_code = d.error_code '
    'FROM transfer.download d, ('
    'SELECT DISTINCT ON (m.download_id) at.id AS attempt_id, m.download_id '
    'FROM transfer.attempt at JOIN transfer.mirror m ON m.id = at.mirror_id '
    'ORDER BY m.download_id, at.started_at DESC, at.created_at DESC) newest '
    'WHERE newest.attempt_id = a.id AND d.id = newest.download_id AND d.error IS NOT NULL',
    # One with an error and no try at all (a torrent, say): a failed try on its primary mirror.
    'INSERT INTO transfer.attempt (id, created_at, updated_at, mirror_id, status, downloaded_size, started_at, '
    'completed_at, error, error_code) '
    'SELECT gen_random_uuid(), now(), now(), first.id, \'failed\', 0, now(), now(), d.error, d.error_code '
    'FROM transfer.download d JOIN LATERAL ('
    'SELECT m.id FROM transfer.mirror m WHERE m.download_id = d.id ORDER BY m.priority, m.created_at LIMIT 1'
    ') first ON true '
    'WHERE d.error IS NOT NULL AND NOT EXISTS ('
    'SELECT 1 FROM transfer.attempt at JOIN transfer.mirror m ON m.id = at.mirror_id WHERE m.download_id = d.id)',
]


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0012_size_names')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        ops.AddField(
            model_name='Attempt',
            name='error',
            field=fields.TextField(null=True, description='Why the try ended badly, when it did; none for a try that completed or was paused.', unique=False),
        ),
        ops.AddField(
            model_name='Attempt',
            name='error_code',
            field=fields.CharField(null=True, max_length=64),
        ),
        ops.RunSQL(_BACKFILL),
        ops.RemoveField(model_name='Download', name='error'),
        ops.RemoveField(model_name='Download', name='error_code'),
    ]
