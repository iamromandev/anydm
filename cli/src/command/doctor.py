import shutil

import typer

from src.core.errors import UsageError
from src.core.names import clean_display


def _line(name: str, found: bool, detail: str) -> str:
    return f"{name}: {'ok' if found else 'missing'} ({detail})"


def _cookies_line() -> None:
    """Whether a cookies file is set and usable. Names the path, never reads out the file."""
    from src.config.settings import get_settings  # lazy: keeps --help fast
    from src.core.cookies import check_cookies

    try:
        path = get_settings().cookies_file
    except UsageError as e:
        typer.echo(f"cookies: unknown ({e})")
        return
    if path is None:
        typer.echo("cookies: not set")
        return
    try:
        checked = check_cookies(path)
    except UsageError as e:
        typer.echo(f"cookies: invalid ({e})")
        return
    typer.echo(f"cookies: ok ({checked.path})")
    if checked.warning:
        typer.echo(f"warning: {checked.warning}")


def _plugin_lines() -> None:
    """One line per installed plugin: its pinned image, whether that image is here, the approved permissions. Asks
    docker only `version` and `image inspect`: nothing is run or pulled."""
    from src.engine import _docker_runner  # lazy: keeps --help fast
    from src.plugin import state

    try:
        plugins = state.load()
        if not plugins:
            return
        runner = _docker_runner()
    except UsageError as e:  # a corrupt plugins.toml, or an invalid ANYDM_MEDIA_IMAGE
        typer.echo(f"plugins: invalid ({e})")
        return
    up = runner.available()
    for plugin in plugins:
        perms = ", ".join(sorted(plugin.permissions, key=("network", "cookies").index)) or "no permissions"
        detail = f"{clean_display(plugin.image)}; {perms}"
        if not up:
            typer.echo(f"plugin {plugin.name}: unknown (docker not running) ({detail})")
            continue
        typer.echo(_line(f"plugin {plugin.name}", runner.image_present(plugin.image), detail))


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
    _cookies_line()
    _plugin_lines()
    typer.echo("torrent: built-in (pure Python client, .torrent and magnet)")
