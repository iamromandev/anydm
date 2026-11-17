import asyncio
import json
from pathlib import Path
from typing import Any

import typer

from src.core.errors import AnydmError, UsageError

INTERRUPTED = 130


def _fail(message: str, code: int) -> typer.Exit:
    typer.secho(f"error: {message}", fg=typer.colors.RED, err=True)
    return typer.Exit(code)


def _size(n: int) -> str:
    value = float(n)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{n} B" if unit == "B" else f"{value:.1f} {unit} ({n} bytes)"
        value /= 1024
    return ""


async def _http(url: str) -> dict[str, Any]:
    import httpx  # lazy: keeps --help fast

    from src.engine.http.engine import MAX_REDIRECTS
    from src.engine.http.probe import probe

    try:
        async with httpx.AsyncClient(
            follow_redirects=True, max_redirects=MAX_REDIRECTS, timeout=httpx.Timeout(30.0, pool=None)
        ) as client:
            found = await probe(client, url)  # a HEAD; a server that refuses it gets a one-byte GET
    except httpx.InvalidURL as e:
        raise UsageError(f"{url!r} is not a usable URL") from e
    return {"title": found.name, "size": found.size, "formats": [], "items": []}


def _print(kind: str, plugin: str | None, fields: dict[str, Any], as_json: bool) -> None:
    if as_json:
        typer.echo(json.dumps({"kind": kind, "plugin": plugin, **fields}))
        return
    if fields["title"]:
        typer.echo(f"title: {fields['title']}")
    if fields["size"] is not None:
        typer.echo(f"size: {_size(fields['size'])}")
    if fields["formats"]:
        typer.echo("formats:")
        for f in fields["formats"]:
            typer.echo("  " + "  ".join(x for x in (f["id"], f["ext"], f["note"]) if x))
    if fields["items"]:
        typer.echo("items:")
        for i in fields["items"]:
            typer.echo("  " + "  ".join(x for x in (i["title"], i["url"]) if x))


def info(
    url: str = typer.Argument(..., help="URL to look up"),
    as_json: bool = typer.Option(False, "--json", help="Print one JSON object"),
    cookies: Path | None = typer.Option(None, "--cookies", help="Netscape cookies file for media that needs a login"),
    plugin: str | None = typer.Option(None, "--plugin", help="Use this installed plugin, without asking the others"),
) -> None:
    """Show what URL is (title, size, formats) without downloading it."""
    from src.config.settings import get_settings  # lazy: keeps --help fast
    from src.core.cookies import check_cookies
    from src.core.progress import NoticeReporter
    from src.engine import Kind, cookies_skipped, media_info, plugin_info, resolve, route_takes_cookies, take_notices

    reporter = NoticeReporter()
    try:
        settings = get_settings(cookies_file=cookies)
        route = resolve(url, None, plugin=plugin, need="info")
        for note in take_notices():  # e.g. media detection skipped because Docker is missing
            reporter.notice(note)
        if route.kind is Kind.TORRENT:
            raise UsageError("info does not look into torrents; use `anydm get`")
        if route.kind in (Kind.MEDIA, Kind.PLUGIN):
            use: Path | None = None
            if settings.cookies_file is not None and route_takes_cookies(route):  # only these read them
                checked = check_cookies(settings.cookies_file)
                if checked.warning:
                    reporter.notice(checked.warning)
                use = checked.path
            elif settings.cookies_file is not None and (skipped := cookies_skipped(route)):
                reporter.notice(skipped)
            if route.kind is Kind.MEDIA:
                found = asyncio.run(media_info(url, use, reporter.notice))
            else:
                found = asyncio.run(plugin_info(route.plugin or "", url, use, reporter.notice))
            fields = {"title": found.title, "size": found.size,
                      "formats": [{"id": f.id, "ext": f.ext, "note": f.note} for f in found.formats],
                      "items": [{"title": i.title, "url": i.url} for i in found.items]}
        else:
            if settings.cookies_file is not None:
                reporter.notice("cookies apply to media only; this link is looked up as plain HTTP without them")
            fields = asyncio.run(_http(url))
    except KeyboardInterrupt:
        raise _fail("interrupted", INTERRUPTED) from None
    except AnydmError as e:
        raise _fail(str(e), e.exit_code) from None
    _print(route.kind.value, route.plugin, fields, as_json)
