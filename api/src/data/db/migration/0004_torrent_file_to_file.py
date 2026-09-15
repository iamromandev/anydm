from tortoise import migrations
from tortoise.migrations import operations as ops
from tortoise.migrations.operations import TortoiseOperation


class _RenameModelKeepingRelations(TortoiseOperation):
    """Like ``ops.RenameModel``, minus a real bug it has.

    ``RenameModel.state_forward`` reloads only the renamed model itself. For a
    model that owns a foreign key — ``File.task`` points at ``Task`` — that
    leaves ``Task``'s own registered backward relations untouched, still
    keyed under the model's old name. Reloading ``File`` then re-registers
    that same backward relation on ``Task`` without the old one ever being
    cleared, and Tortoise raises ``backward relation "..." duplicates in
    model Task``.

    ``DeleteModel``/``CreateModel`` do not have this problem — both reload
    every model the fields they touch reference — which is what this copies:
    unregister the old name, rename in state, then reload both the model and
    everything its fields reference.

    Database-side this is a no-op. The real rename is the explicit ``RunSQL``
    below; this operation exists purely to keep migration state in sync with
    it for whatever migration reads it next.
    """

    reduces_to_sql = False

    def __init__(self, old_name: str, new_name: str) -> None:
        self.old_name = old_name
        self.new_name = new_name

    def describe(self) -> str:
        return f"Rename model {self.old_name} to {self.new_name} (state only)"

    def state_forward(self, app_label, state) -> None:
        model_state = state.models.pop((app_label, self.old_name))
        state.apps.unregister_model(app_label, self.old_name)

        model_state.name = self.new_name
        state.models[(app_label, self.new_name)] = model_state

        to_reload = {self.new_name}
        for field in model_state.fields.values():
            model_name = getattr(field, "model_name", None)
            if model_name:
                to_reload.add(state.apps.split_reference(model_name)[1])
        state.reload_models({(app_label, name) for name in to_reload})

    async def database_forward(self, app_label, old_state, new_state, state_editor=None) -> None:
        return None

    async def database_backward(self, app_label, old_state, new_state, state_editor=None) -> None:
        return None


class Migration(migrations.Migration):
    dependencies = [('model', '0003_torrent')]

    initial = False

    operations = [
        _RenameModelKeepingRelations('TorrentFile', 'File'),
        # Brings the table option in migration state in line with the model,
        # so a later migration against "File" generates SQL against the right
        # table. Emits no SQL of its own.
        ops.AlterModelOptions(
            name='File',
            options={'table': 'file', 'table_description': 'File'},
        ),
        # The actual rename. Constraint and index names inherited from
        # "torrent_file" are left as they are — Postgres does not require them
        # to match the table name, and renaming them buys nothing.
        ops.RunSQL(
            'ALTER TABLE "torrent_file" RENAME TO "file"',
            reverse_sql='ALTER TABLE "file" RENAME TO "torrent_file"',
        ),
    ]
