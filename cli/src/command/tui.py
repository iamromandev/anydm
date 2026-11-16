import typer

from src.core.errors import AnydmError


def tui() -> None:
    """Open the full-screen download queue."""
    from src.config.settings import get_settings  # lazy: keeps --help fast

    try:
        settings = get_settings()
    except AnydmError as e:
        typer.secho(f"error: {e}", fg=typer.colors.RED, err=True)
        raise typer.Exit(e.exit_code) from None
    from src.tui.app import AnydmApp  # lazy: Textual loads only for this command

    AnydmApp(settings, settings.max_active).run()
