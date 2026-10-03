"""The account models build a schema and relate the way the repositories read them.

In-memory SQLite: no Postgres needed, so this runs with the unit suite.
"""

import pytest
from src.data.db.model import Session, Share, User, UserSetting
from src.data.type import RefType, SessionKind, ShareRole, UserRole
from tortoise.exceptions import IntegrityError


async def a_user(name: str, role: UserRole = UserRole.USER) -> User:
    user = User(username=name, display_name=name, password_hash="x", role=role)
    user.path = str(user.id)
    await user.save()
    return user


@pytest.mark.asyncio
async def test_a_user_defaults_and_its_folder_is_its_id(sqlite: None) -> None:
    user = await a_user("ada")

    assert user.role == UserRole.USER
    assert user.is_active is True
    assert user.last_login_at is None
    assert user.path == str(user.id)


@pytest.mark.asyncio
async def test_a_username_is_unique(sqlite: None) -> None:
    await a_user("ada")

    with pytest.raises(IntegrityError):
        await a_user("ada")


@pytest.mark.asyncio
async def test_a_session_belongs_to_its_user_and_goes_with_it(sqlite: None) -> None:
    user = await a_user("ada")
    await Session.create(user=user, kind=SessionKind.API, name="cli", token_hash="h" * 64, user_agent="")

    assert await Session.filter(user=user).count() == 1
    await user.delete()
    assert await Session.all().count() == 0


@pytest.mark.asyncio
async def test_a_token_hash_is_unique(sqlite: None) -> None:
    user = await a_user("ada")
    await Session.create(user=user, kind=SessionKind.SESSION, token_hash="h" * 64, user_agent="")

    with pytest.raises(IntegrityError):
        await Session.create(user=user, kind=SessionKind.SESSION, token_hash="h" * 64, user_agent="")


@pytest.mark.asyncio
async def test_a_share_names_who_granted_it_and_to_whom(sqlite: None) -> None:
    owner, friend = await a_user("owner"), await a_user("friend")
    ref = owner.id  # any uuid: a share carries no foreign key to its target

    share = await Share.create(
        ref_type=RefType.DOWNLOAD, ref_id=ref, user=friend, role=ShareRole.VIEW, created_by=owner
    )

    assert share.user_id == friend.id
    assert share.created_by_id == owner.id
    assert await Share.filter(created_by=owner).count() == 1


@pytest.mark.asyncio
async def test_one_share_per_ref_and_user(sqlite: None) -> None:
    owner, friend = await a_user("owner"), await a_user("friend")
    await Share.create(
        ref_type=RefType.DOWNLOAD, ref_id=owner.id, user=friend, role=ShareRole.VIEW, created_by=owner
    )

    with pytest.raises(IntegrityError):
        await Share.create(
            ref_type=RefType.DOWNLOAD,
            ref_id=owner.id,
            user=friend,
            role=ShareRole.MANAGE,
            created_by=owner,
        )


@pytest.mark.asyncio
async def test_a_share_to_everyone_has_no_user(sqlite: None) -> None:
    owner = await a_user("owner")

    share = await Share.create(
        ref_type=RefType.COLLECTION, ref_id=owner.id, user=None, role=ShareRole.VIEW, created_by=owner
    )

    assert share.user_id is None


@pytest.mark.asyncio
async def test_a_setting_is_one_value_per_user_and_key(sqlite: None) -> None:
    user = await a_user("ada")
    await UserSetting.create(user=user, key="theme", value={"mode": "dark"})

    assert (await UserSetting.get(user=user, key="theme")).value == {"mode": "dark"}
    with pytest.raises(IntegrityError):
        await UserSetting.create(user=user, key="theme", value={"mode": "light"})


@pytest.mark.asyncio
async def test_a_user_with_a_grant_cannot_be_deleted(sqlite: None) -> None:
    owner, friend = await a_user("owner"), await a_user("friend")
    await Share.create(
        ref_type=RefType.DOWNLOAD, ref_id=owner.id, user=friend, role=ShareRole.VIEW, created_by=owner
    )

    with pytest.raises(IntegrityError):
        await owner.delete()
