import pytest
from src.config.settings import Settings
from src.plugin.model import Plugin, media_plugin


def test_the_media_plugin_uses_the_settings_image_and_all_permissions() -> None:
    p = media_plugin(Settings(media_image="anydm-media:9"))
    assert (p.name, p.image, p.builtin) == ("media", "anydm-media:9", True)
    assert p.permissions == {"network", "cookies"}
    assert p.capabilities == {"check", "download", "info"}


def test_a_plugin_is_immutable() -> None:
    p = Plugin("x1", "img", "1", frozenset(), frozenset())
    assert p.builtin is False
    with pytest.raises(AttributeError):
        p.name = "y"  # ty: ignore[invalid-assignment]
