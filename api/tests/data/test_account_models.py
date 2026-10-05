"""The account model builds a schema and relates the way the repositories read it.

In-memory SQLite: no Postgres needed, so this runs with the unit suite.
"""

import pytest
from src.data.db.model import User
from src.data.type import UserRole, UserStatus
from tortoise.exceptions import IntegrityError


async def a_user(name: str, role: UserRole = UserRole.USER) -> User:
    return await User.create(username=name, display_name=name, password_hash="x", role=role)


@pytest.mark.asyncio
async def test_a_user_defaults(sqlite: None) -> None:
    user = await a_user("ada")

    assert user.role == UserRole.USER
    assert user.status is UserStatus.ACTIVE
    assert user.last_login_at is None


@pytest.mark.asyncio
async def test_a_username_is_unique(sqlite: None) -> None:
    await a_user("ada")

    with pytest.raises(IntegrityError):
        await a_user("ada")


@pytest.mark.asyncio
async def test_a_user_has_no_locale_or_time_zone_until_set(sqlite: None) -> None:
    user = await a_user("ada")

    assert (await User.get(id=user.id)).locale is None
    assert (await User.get(id=user.id)).timezone is None


@pytest.mark.asyncio
async def test_a_user_keeps_a_locale_and_a_time_zone(sqlite: None) -> None:
    """A BCP 47 tag and an IANA zone, the two things a client formats every date and number by."""
    user = await a_user("ada")
    user.locale, user.timezone = "bn-BD", "Asia/Dhaka"
    await user.save()

    found = await User.get(id=user.id)
    assert (found.locale, found.timezone) == ("bn-BD", "Asia/Dhaka")


def test_there_is_no_server_side_settings_table() -> None:
    """UI preferences live in the browser; only the account's locale and time zone are stored."""
    from src.data.db import model

    assert not hasattr(model, "UserSetting")


def test_logins_and_sharing_are_not_modelled_until_they_are_built() -> None:
    """A table nothing uses is a guess about how it will be queried; phase 2 designs both with their users."""
    from src.data.db import model

    assert not hasattr(model, "Session")
    assert not hasattr(model, "Share")
