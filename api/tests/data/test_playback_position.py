"""PlaybackPosition: where one file was left in the player, in the play schema."""

from src.data.db.model import PlaybackPosition
from tortoise import fields
from tortoise.fields.relational import ForeignKeyFieldInstance, OneToOneFieldInstance


def test_playback_position_columns_and_defaults() -> None:
    fields_map = PlaybackPosition._meta.fields_map
    assert isinstance(fields_map["position_seconds"], fields.FloatField)
    assert isinstance(fields_map["duration_seconds"], fields.FloatField)
    assert fields_map["position_seconds"].default == 0.0
    assert fields_map["duration_seconds"].default == 0.0
    assert fields_map["watched"].default is False
    assert "updated_at" in fields_map


def test_playback_position_is_one_per_file_and_goes_with_it() -> None:
    # A plain FK, not one-to-one: profiles (#421) can add a user to the key.
    field = PlaybackPosition._meta.fields_map["file"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert not isinstance(field, OneToOneFieldInstance)
    assert field.model_name == "model.File"
    assert getattr(field, "on_delete", None) == "CASCADE"
    assert ("file",) in PlaybackPosition.Meta.unique_together


def test_playback_position_lives_in_play() -> None:
    assert (PlaybackPosition.Meta.table, PlaybackPosition.Meta.schema) == ("playback_position", "play")
