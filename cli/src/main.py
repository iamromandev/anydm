import importlib.metadata

import typer

from src.command.doctor import doctor
from src.command.get import get
from src.command.info import info
from src.command.plugin import app as plugin_app
from src.command.tui import tui

app = typer.Typer(
    name="anydm",
    help="AnyDM command-line downloader.",
    no_args_is_help=True,
    pretty_exceptions_show_locals=False,  # a traceback must never print local variables: URLs, passkeys, peer ids
)
app.command()(get)
app.command()(info)
app.command()(doctor)
app.command()(tui)
app.add_typer(plugin_app, name="plugin")


def _version(value: bool) -> None:
    if value:
        typer.echo(importlib.metadata.version("anydm-cli"))
        raise typer.Exit()


@app.callback()
def root(
    version: bool = typer.Option(False, "--version", callback=_version, is_eager=True, help="Print the version"),
) -> None:
    """AnyDM command-line downloader."""


def main() -> None:
    app()


if __name__ == "__main__":
    main()
