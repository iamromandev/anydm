import shutil

import typer

from src.core.errors import UsageError


def _line(name: str, found: bool, detail: str) -> str:
    return f"{name}: {'ok' if found else 'missing'} ({detail})"


def doctor() -> None:
    """Check the tools anydm can use. Makes no network requests."""
    from src.engine import _docker_runner, _has_local_yt_dlp  # lazy: keeps --help fast

    if _has_local_yt_dlp():
        checks = [
            ("ffmpeg", shutil.which("ffmpeg") is not None, "merges media formats"),
            ("deno", shutil.which("deno") is not None, "full YouTube support"),
            ("yt_dlp", True, "media runs in-process"),
        ]
    else:
        try:
            runner = _docker_runner()
        except UsageError as e:  # an invalid ANYDM_MEDIA_IMAGE, say
            typer.secho(f"error: {e}", fg=typer.colors.RED, err=True)
            raise typer.Exit(e.exit_code) from None
        up = runner.available()
        present = up and runner.image_present()
        hint = "built locally with `make build-media`, or pulled on first use"
        checks = [
            ("docker", up, "media downloads run in containers" if up else "needed for media downloads"),
            ("media image", present, runner.image if present else f"{runner.image}; {hint}"),
        ]
    for name, found, detail in checks:
        typer.echo(_line(name, found, detail))
    typer.echo("torrent: built-in (pure Python client, .torrent and magnet)")
