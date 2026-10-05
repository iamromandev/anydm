"""User: its status is an enum, not a boolean; admins and users are roles on the same row."""

from src.data.db.model.iam.actor.user import User
from src.data.type import UserStatus


def test_user_has_a_status_enum() -> None:
    field = User._meta.fields_map["status"]
    assert getattr(field, "enum_type", None) is UserStatus
    assert field.null is False
    assert field.default is UserStatus.ACTIVE


def test_user_dropped_is_active_boolean() -> None:
    assert "is_active" not in User._meta.fields_map


def test_user_status_values() -> None:
    assert (UserStatus.ACTIVE, UserStatus.INACTIVE) == ("active", "inactive")
