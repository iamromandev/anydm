"""Tracker/Peer/Piece: a torrent's trackers, peers, and pieces, each with status and progress."""

from src.data.db.model.torrent.peer import Peer
from src.data.db.model.torrent.piece import Piece
from src.data.db.model.torrent.tracker import Tracker
from src.data.type import PeerStatus, PieceStatus, TrackerStatus
from tortoise.fields.relational import ForeignKeyFieldInstance


def _fk(model, name, related):
    field = model._meta.fields_map[name]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Torrent"
    assert field.related_name == related
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_tracker_shape() -> None:
    _fk(Tracker, "torrent", "trackers")
    fields_map = Tracker._meta.fields_map
    from tortoise import fields

    assert isinstance(fields_map["url"], fields.TextField)
    assert isinstance(fields_map["tier"], fields.IntField)
    assert fields_map["tier"].default == 0
    assert fields_map["status"].default is TrackerStatus.ACTIVE
    assert any(frozenset(c) == frozenset({"torrent", "url"}) for c in Tracker.Meta.unique_together)


def test_peer_shape() -> None:
    _fk(Peer, "torrent", "peers")
    fields_map = Peer._meta.fields_map
    from tortoise import fields

    assert fields_map["address"].max_length == 255
    assert isinstance(fields_map["port"], fields.IntField)
    assert fields_map["port"].null is False
    assert fields_map["client"].null is True
    assert fields_map["status"].default is PeerStatus.CONNECTING
    assert fields_map["downloaded_bytes"].default == 0
    assert fields_map["uploaded_bytes"].default == 0
    assert fields_map["last_seen_at"].null is True
    assert any(frozenset(c) == frozenset({"torrent", "address", "port"}) for c in Peer.Meta.unique_together)


def test_piece_shape() -> None:
    _fk(Piece, "torrent", "pieces")
    fields_map = Piece._meta.fields_map
    from tortoise import fields

    assert isinstance(fields_map["index"], fields.IntField)
    assert fields_map["index"].null is False
    assert isinstance(fields_map["size"], fields.BigIntField)
    assert fields_map["hash"].max_length == 64
    assert fields_map["status"].default is PieceStatus.PENDING
    assert fields_map["downloaded_bytes"].default == 0
    assert any(frozenset(c) == frozenset({"torrent", "index"}) for c in Piece.Meta.unique_together)


def test_statuses() -> None:
    assert (TrackerStatus.ACTIVE, TrackerStatus.INACTIVE, TrackerStatus.FAILED) == ("active", "inactive", "failed")
    assert (PeerStatus.CONNECTING, PeerStatus.CONNECTED, PeerStatus.DISCONNECTED) == ("connecting", "connected", "disconnected")
    assert (PieceStatus.PENDING, PieceStatus.DOWNLOADING, PieceStatus.COMPLETED, PieceStatus.FAILED) == (
        "pending",
        "downloading",
        "completed",
        "failed",
    )


def test_meta_is_transfer() -> None:
    for model, table in ((Tracker, "tracker"), (Peer, "peer"), (Piece, "piece")):
        assert model.Meta.table == table
        assert model.Meta.schema == "transfer"
