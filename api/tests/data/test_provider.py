"""Provider: one upstream; its key is unique, its base_url is the Url, and deleting the Url takes the Provider."""

from src.data.db.model.transfer.provider import Provider
from src.data.type import ProviderStatus
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_provider_base_url_is_a_required_fk_to_url() -> None:
    field = Provider._meta.fields_map["base_url"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Url"
    assert field.related_name == "provider"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_provider_slug_is_unique_varchar_64() -> None:
    field = Provider._meta.fields_map["slug"]
    assert field.null is False
    assert field.unique is True
    assert field.max_length == 64


def test_provider_name_is_varchar_64() -> None:
    field = Provider._meta.fields_map["name"]
    assert field.max_length == 64
    assert field.null is False


def test_provider_status_defaults_to_active() -> None:
    field = Provider._meta.fields_map["status"]
    assert field.default == ProviderStatus.ACTIVE


def test_provider_status_is_active_inactive() -> None:
    assert (ProviderStatus.ACTIVE, ProviderStatus.INACTIVE) == ("active", "inactive")


def test_provider_dropped_the_old_fields() -> None:
    names = set(Provider._meta.fields_map)
    assert "url" not in names
    assert "key" not in names
    assert "api_key" not in names
