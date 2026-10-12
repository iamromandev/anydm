from typing import ClassVar

from tortoise import fields, migrations
from tortoise.fields.base import OnDelete
from tortoise.migrations import operations as ops
from tortoise.migrations.constraints import UniqueConstraint

# Torrents become content, shared by the sources that lead to them and the downloads of them in turn.
#
# - Source.torrent replaces Torrent.source: several sources can lead to one torrent.
# - TorrentFile gets rqbit's file index, and a download's File points at its TorrentFile.
# - Which pieces a download has moves from a status per Piece row to Download.pieces, one bit per piece.
# - Peer, live state of a transfer, belongs to the download instead of the torrent.
#
# Forward only. Piece status and peers are live state that rqbit fills again within a tick, so what
# the old shape held is not rebuilt on the way down.


async def _bitmaps(apps, schema_editor) -> None:
    """Fold each torrent's completed Piece rows into the bitmap of every download of it."""
    client = schema_editor.client
    rows = await client.execute_query_dict(
        'SELECT t.id AS torrent_id, p."index" AS piece, p.status AS status '
        'FROM torrent.piece p JOIN torrent.torrent t ON t.id = p.torrent_id ORDER BY t.id, p."index"'
    )
    by_torrent: dict = {}
    for row in rows:
        by_torrent.setdefault(row['torrent_id'], []).append(row)
    for torrent_id, pieces in by_torrent.items():
        done = [piece['piece'] for piece in pieces if piece['status'] == 'completed']
        if not done:
            continue
        # rqbit's own order: piece 0 is the top bit of the first byte.
        bits = bytearray((max(piece['piece'] for piece in pieces) + 1 + 7) // 8)
        for index in done:
            bits[index // 8] |= 1 << (7 - index % 8)
        await client.execute_query(
            'UPDATE transfer.download SET pieces = $1 WHERE id IN ('
            'SELECT m.download_id FROM transfer.mirror m JOIN torrent.torrent t ON t.source_id = m.source_id '
            'WHERE t.id = $2)',
            [bytes(bits), torrent_id],
        )


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0010_category_rules')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[ops.Operation]] = [
        # Peers are live state and nothing writes them yet; they are cleared so the required download key can be added.
        ops.RunSQL('DELETE FROM torrent.peer'),
        ops.RemoveConstraint(
            model_name='Peer',
            name=None,
            fields=['torrent', 'address', 'port'],
        ),
        ops.AddField(
            model_name='Peer',
            name='download',
            field=fields.ForeignKeyField('model.Download', source_field='download_id', db_constraint=True, to_field='id', related_name='peers', on_delete=OnDelete.CASCADE),
        ),
        ops.RemoveField(model_name='Peer', name='torrent'),
        ops.AddConstraint(
            model_name='Peer',
            constraint=UniqueConstraint(fields=('download', 'address', 'port'), name=None),
        ),
        ops.AddField(
            model_name='Download',
            name='pieces',
            field=fields.BinaryField(null=True, description="For a torrent, the pieces it has, one bit each in rqbit's own order (piece 0 the top bit); none until known."),
        ),
        ops.AddField(
            model_name='Source',
            name='torrent',
            field=fields.ForeignKeyField('model.Torrent', source_field='torrent_id', null=True, description='The torrent a torrent source leads to, none for any other kind; several sources can lead to one.', db_constraint=True, to_field='id', related_name='sources', on_delete=OnDelete.SET_NULL),
        ),
        ops.AddField(
            model_name='File',
            name='torrent_file',
            field=fields.ForeignKeyField('model.TorrentFile', source_field='torrent_file_id', null=True, description="For a torrent's file, the torrent's own row for it (its ``index`` is this one's); none otherwise.", db_constraint=True, to_field='id', related_name='download_files', on_delete=OnDelete.SET_NULL),
        ),
        # Nullable until the rows that exist have an index.
        ops.AddField(
            model_name='TorrentFile',
            name='index',
            field=fields.IntField(null=True),
        ),
        ops.RunSQL(
            [
                # Each torrent source leads to the torrent that pointed at it.
                'UPDATE catalog.source s SET torrent_id = t.id FROM torrent.torrent t WHERE t.source_id = s.id',
                # A torrent's own file index is the one its downloads' File rows already carry, matched by path.
                'UPDATE torrent.torrent_file tf SET "index" = f."index" '
                'FROM transfer.file f JOIN transfer.mirror m ON m.download_id = f.download_id '
                'JOIN torrent.torrent t ON t.source_id = m.source_id '
                'WHERE tf.torrent_id = t.id AND tf.path = f.path',
                # No download left to give it an index: the torrent is read from its metadata again when added.
                'DELETE FROM torrent.torrent_file WHERE "index" IS NULL',
                'UPDATE transfer.file f SET torrent_file_id = tf.id '
                'FROM transfer.mirror m JOIN torrent.torrent t ON t.source_id = m.source_id '
                'JOIN torrent.torrent_file tf ON tf.torrent_id = t.id '
                'WHERE m.download_id = f.download_id AND tf."index" = f."index"',
            ]
        ),
        ops.RunPython(_bitmaps),
        ops.AlterField(
            model_name='TorrentFile',
            name='index',
            field=fields.IntField(),
        ),
        ops.AddConstraint(
            model_name='TorrentFile',
            constraint=UniqueConstraint(fields=('torrent', 'index'), name=None),
        ),
        ops.RemoveField(model_name='Piece', name='downloaded_bytes'),
        ops.RemoveField(model_name='Piece', name='status'),
        ops.RemoveField(model_name='Torrent', name='source'),
        ops.AlterField(
            model_name='Download',
            name='folder',
            field=fields.TextField(null=True, unique=False),
        ),
    ]
