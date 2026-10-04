"""A preference is one setting of one person: a known key and a JSON value."""

import pytest
from src.data.db.model import Preference, User
from src.data.type import PreferenceKey
from tortoise.exceptions import IntegrityError, ValidationError


async def a_user(name: str) -> User:
    user = User(username=name, display_name=name, password_hash="x")
    user.path = str(user.id)
    await user.save()
    return user


def test_the_keys_are_the_settings_the_web_app_keeps() -> None:
    assert {key.value for key in PreferenceKey} == {
        "default_preset",
        "confirm_before_remove",
        "audio_language",
        "subtitle_language",
        "theme",
        "sort",
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("key", "value"),
    [
        (PreferenceKey.DEFAULT_PRESET, "1080"),
        (PreferenceKey.CONFIRM_BEFORE_REMOVE, False),
        (PreferenceKey.AUDIO_LANGUAGE, ""),
        (PreferenceKey.THEME, {"mode": "dark"}),
    ],
)
async def test_a_preference_keeps_any_json_value(sqlite: None, key: PreferenceKey, value: object) -> None:
    user = await a_user("ada")

    row = await Preference.create(user=user, key=key, value={"value": value})

    assert (await Preference.get(id=row.id)).value == {"value": value}


@pytest.mark.asyncio
async def test_a_bare_string_cannot_be_stored_so_a_setting_goes_inside_an_object(sqlite: None) -> None:
    """Tortoise's JSONField reads a ``str`` as JSON text; ``{"value": ...}`` is how a string setting is kept."""
    user = await a_user("ada")

    with pytest.raises(Exception, match="invalid json"):
        await Preference.create(user=user, key=PreferenceKey.THEME, value="dark")


@pytest.mark.asyncio
async def test_a_person_has_one_value_per_key(sqlite: None) -> None:
    user = await a_user("ada")
    await Preference.create(user=user, key=PreferenceKey.THEME, value={"value": "dark"})

    with pytest.raises(IntegrityError):
        await Preference.create(user=user, key=PreferenceKey.THEME, value={"value": "light"})


@pytest.mark.asyncio
async def test_two_people_can_each_set_the_same_key(sqlite: None) -> None:
    ada, bob = await a_user("ada"), await a_user("bob")

    await Preference.create(user=ada, key=PreferenceKey.THEME, value={"value": "dark"})
    await Preference.create(user=bob, key=PreferenceKey.THEME, value={"value": "light"})

    assert (await Preference.get(user=ada, key=PreferenceKey.THEME)).value == {"value": "dark"}
    assert (await Preference.get(user=bob, key=PreferenceKey.THEME)).value == {"value": "light"}


@pytest.mark.asyncio
async def test_an_unknown_key_is_refused(sqlite: None) -> None:
    user = await a_user("ada")

    with pytest.raises((ValueError, ValidationError)):
        await Preference.create(user=user, key="theem", value={"value": "dark"})


@pytest.mark.asyncio
async def test_a_persons_preferences_go_with_them(sqlite: None) -> None:
    user = await a_user("ada")
    await Preference.create(user=user, key=PreferenceKey.THEME, value={"value": "dark"})
    await Preference.create(user=user, key=PreferenceKey.SORT, value={"value": "title"})

    await user.delete()

    assert await Preference.all().count() == 0
