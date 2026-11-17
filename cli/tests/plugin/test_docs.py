"""cli/docs/plugins.md is the contract for plugin authors: it must cover everything contract.py knows."""

from pathlib import Path

import pytest
from src.plugin.contract import CAPABILITIES, LINE_TYPES, PERMISSIONS, PROTOCOL

CLI_DIR = Path(__file__).parents[2]
DOC = CLI_DIR / "docs" / "plugins.md"
COMMANDS = ("manifest", "check", "download", "info")


@pytest.fixture(scope="module")
def doc() -> str:
    assert DOC.is_file(), f"{DOC} is missing"
    return DOC.read_text()


@pytest.mark.parametrize("line_type", sorted(LINE_TYPES))
def test_every_line_type_has_an_example(doc: str, line_type: str) -> None:
    assert f'{{"type":"{line_type}"' in doc


@pytest.mark.parametrize("command", COMMANDS)
def test_every_command_is_described(doc: str, command: str) -> None:
    assert f"`{command}`" in doc


@pytest.mark.parametrize("word", sorted(CAPABILITIES | PERMISSIONS))
def test_every_capability_and_permission_is_named(doc: str, word: str) -> None:
    assert f"`{word}`" in doc


def test_the_doc_states_the_current_protocol(doc: str) -> None:
    assert f"protocol {PROTOCOL}" in doc.lower()


def test_the_readme_links_the_doc_and_documents_the_commands() -> None:
    readme = (CLI_DIR / "README.md").read_text()
    assert "docs/plugins.md" in readme
    for command in ("anydm plugin add", "anydm plugin list", "anydm plugin remove", "anydm plugin order", "anydm info"):
        assert command in readme
    assert "make build-media" in readme and "protocol 3" in readme.lower()
