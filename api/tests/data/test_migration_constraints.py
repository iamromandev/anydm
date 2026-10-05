"""Every constraint a model declares must be added by a migration.

``makemigrations`` writes a model's ``Meta.constraints`` into the ``CreateModel``
options, and that form emits no DDL: the migration state agrees with the models,
``makemigrations`` reports no changes, and the index is not in the database. Only
an explicit ``ops.AddConstraint`` creates it, so a regenerated migration needs
those appended by hand, as exateks/auth found twice.

Read from the migration source, so this runs with the unit suite.
"""

import ast
from pathlib import Path

from src.data.db import model as model_package
from tortoise import Model

MIGRATION_DIR = Path(__file__).resolve().parents[2] / "src" / "data" / "db" / "migration"


def _added_constraint_names() -> set[str]:
    names: set[str] = set()
    for path in sorted(MIGRATION_DIR.glob("[0-9]*.py")):
        for node in ast.walk(ast.parse(path.read_text())):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)):
                continue
            if node.func.attr != "AddConstraint":
                continue
            constraint = next((k.value for k in node.keywords if k.arg == "constraint"), None)
            if not isinstance(constraint, ast.Call):
                continue
            name = next((k.value for k in constraint.keywords if k.arg == "name"), None)
            if isinstance(name, ast.Constant) and isinstance(name.value, str):
                names.add(name.value)
    return names


def _model_classes() -> list[type[Model]]:
    return [attr for attr in vars(model_package).values() if isinstance(attr, type) and issubclass(attr, Model)]


def _declared_constraint_names() -> dict[str, str]:
    """Constraint name -> the model that declares it."""
    declared: dict[str, str] = {}
    for attr in _model_classes():
        for constraint in getattr(getattr(attr, "Meta", None), "constraints", ()):
            if name := getattr(constraint, "name", None):
                declared[name] = attr.__name__
    return declared


def test_every_declared_constraint_is_added_by_a_migration() -> None:
    # The guard is on finding the models, not their constraints: no model may
    # declare one, and then there is nothing to check, rightly.
    assert len(_model_classes()) > 10, "the model package was not read: this test is checking nothing"
    declared = _declared_constraint_names()

    added = _added_constraint_names()
    missing = {name: owner for name, owner in declared.items() if name not in added}
    assert not missing, f"declared on the model but no migration adds them with AddConstraint: {missing}"
