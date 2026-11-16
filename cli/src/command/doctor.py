import importlib.util
import shutil

import typer


def _line(name: str, found: bool, detail: str) -> str:
    return f"{name}: {'ok' if found else 'missing'} ({detail})"


def doctor() -> None:
    """Check the tools anydm can use. Makes no network requests."""
    checks = [
        ("ffmpeg", shutil.which("ffmpeg") is not None, "merges media formats"),
        ("deno", shutil.which("deno") is not None, "full YouTube support"),
        ("yt_dlp", importlib.util.find_spec("yt_dlp") is not None, "media downloads"),
    ]
    for name, found, detail in checks:
        typer.echo(_line(name, found, detail))
    typer.echo("torrent: built-in (pure Python client, .torrent and magnet)")
