"""Preference enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class PreferenceKey(StrEnum):
    """The settings a person can keep on the server. A new setting is one value here, not a migration."""

    #: What the hero input starts on for a YouTube link.
    DEFAULT_PRESET = "default_preset"
    #: Whether removing a download asks first.
    CONFIRM_BEFORE_REMOVE = "confirm_before_remove"
    #: The audio track the player opens with, by language; "" for whichever the file marks.
    AUDIO_LANGUAGE = "audio_language"
    #: The subtitles shown from the start, by language; "" for none.
    SUBTITLE_LANGUAGE = "subtitle_language"
    #: Light or dark.
    THEME = "theme"
    #: How the download list is ordered.
    SORT = "sort"
