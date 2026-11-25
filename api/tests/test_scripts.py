"""The scripts the makefile and the API container run: `python -m scripts.<name>` must import and expose main."""

import asyncio
import importlib
from types import SimpleNamespace

import pytest


@pytest.mark.parametrize("name", ["scripts.migrate", "scripts.test_db"])
def test_script_has_main(name: str) -> None:
    module = importlib.import_module(name)
    assert callable(module.main)


def test_test_db_refuses_a_database_not_named_test(monkeypatch: pytest.MonkeyPatch) -> None:
    test_db = importlib.import_module("scripts.test_db")
    monkeypatch.setattr(test_db, "get_settings", lambda: SimpleNamespace(db_name="db-anydm"))
    with pytest.raises(SystemExit, match="not a \\*_test database"):
        asyncio.run(test_db.main())
