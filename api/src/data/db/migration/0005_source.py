from typing import ClassVar
from uuid import uuid4

from tortoise import fields, migrations
from tortoise.fields.db_defaults import Now
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import Operation
from tortoise.migrations.schema_editor.base import BaseSchemaEditor
from tortoise.migrations.schema_generator.state_apps import StateApps


async def backfill_kind(apps: StateApps, editor: BaseSchemaEditor) -> None:
    """Every existing row is registry-named (only the seed writes), so kind follows the name."""
    from src.lib.sources.registry import BUILTINS

    SearchSource = apps.get_model("model", "SearchSource")
    for name in BUILTINS:
        await SearchSource.filter(name=name, kind__isnull=True).update(kind=name)
    # Any row added between the column landing and this step still needs a kind.
    await SearchSource.filter(kind__isnull=True).update(kind="torznab")


async def unfill_kind(apps: StateApps, editor: BaseSchemaEditor) -> None:
    SearchSource = apps.get_model("model", "SearchSource")

    await SearchSource.filter(kind__isnull=False).update(kind=None)


async def seed_registry_rows(apps: StateApps, editor: BaseSchemaEditor) -> None:
    """A registry row for whatever the seed has not created yet, at the registry's defaults."""
    from src.lib.sources.registry import BUILTINS

    SearchSource = apps.get_model("model", "SearchSource")
    stored = {source.name for source in await SearchSource.all()}  # ty: ignore[unresolved-attribute]
    for builtin in BUILTINS.values():
        if builtin.name not in stored:
            await SearchSource.create(
                name=builtin.name,
                kind=builtin.name,
                enabled=builtin.default_enabled,
                base_url=builtin.default_url,
            )


async def unseed_registry_rows(apps: StateApps, editor: BaseSchemaEditor) -> None:
    """Remove only rows still at the defaults this step wrote; an edited row is the user's, not ours."""
    from src.lib.sources.registry import BUILTINS

    SearchSource = apps.get_model("model", "SearchSource")
    for builtin in BUILTINS.values():
        await SearchSource.filter(
            name=builtin.name,
            kind=builtin.name,
            enabled=builtin.default_enabled,
            base_url=builtin.default_url,
            api_key__isnull=True,
        ).delete()


class Migration(migrations.Migration):
    dependencies: ClassVar[list[tuple[str, str]]] = [('model', '0004_search_source')]

    initial: ClassVar[bool] = False

    operations: ClassVar[list[Operation]] = [
        ops.AddField(
            model_name='SearchSource',
            name='kind',
            field=fields.CharField(max_length=16, null=True),
        ),
        ops.RunPython(backfill_kind, unfill_kind),
        ops.AlterField(
            model_name='SearchSource',
            name='kind',
            field=fields.CharField(max_length=16),
        ),
        ops.RunPython(seed_registry_rows, unseed_registry_rows),
        ops.AddField(
            model_name='SearchSource',
            name='api_key',
            field=fields.CharField(null=True, max_length=1024),
        ),
        ops.CreateModel(
            name='AppFlag',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('updated_at', fields.DatetimeField(db_index=True, db_default=Now(), auto_now=True, auto_now_add=False)),
                ('name', fields.CharField(unique=True, max_length=128)),
                ('done_at', fields.DatetimeField(auto_now=False, auto_now_add=True)),
            ],
            options={'table': 'app_flag', 'app': 'model', 'pk_attr': 'id', 'table_description': 'AppFlag'},
            bases=['LinkBase'],
        ),
    ]
