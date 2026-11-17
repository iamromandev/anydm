import asyncio
import sys
from enum import StrEnum
from pathlib import Path

import typer

from src.core.errors import AnydmError, PartialDownload
from src.core.paths import writable_dir

INTERRUPTED = 130


class KindOption(StrEnum):
    AUTO = "auto"
    HTTP = "http"
    MEDIA = "media"
    TORRENT = "torrent"


def _fail(message: str, code: int) -> typer.Exit:
    typer.secho(f"error: {message}", fg=typer.colors.RED, err=True)
    return typer.Exit(code)


def get(
    source: str = typer.Argument(..., help="URL, magnet link or .torrent file"),
    output: Path | None = typer.Option(None, "-o", "--output", help="Folder to save into"),
    connections: int | None = typer.Option(None, "-c", "--connections", help="Parallel connections, 1 to 16"),
    kind: KindOption = typer.Option(KindOption.AUTO, "--kind", help="Force the kind of download"),
    fmt: str | None = typer.Option(None, "-f", "--format", help="yt-dlp format string (media)"),
    playlist: bool = typer.Option(False, "--playlist", help="Download a whole playlist (media)"),
    seed: bool = typer.Option(False, "--seed", help="Keep seeding after a torrent finishes"),
    quiet: bool = typer.Option(False, "--quiet", help="No progress output"),
    insecure: bool = typer.Option(False, "--insecure", help="Do not verify TLS certificates"),
    cookies: Path | None = typer.Option(None, "--cookies", help="Netscape cookies file for media that needs a login"),
    plugin: str | None = typer.Option(None, "--plugin", help="Use this installed plugin, without asking the others"),
) -> None:
    """Download SOURCE and print the saved paths."""
    from src.config.settings import get_settings  # lazy: keeps --help fast
    from src.core.cookies import check_cookies
    from src.core.progress import NoticeReporter, NullReporter, Reporter, RichReporter
    from src.engine import Job, Kind, cookies_skipped, engine_for, resolve, route_takes_cookies, take_notices

    reporter: Reporter
    if quiet:
        reporter = NullReporter()
    elif sys.stderr.isatty():
        reporter = RichReporter()
    else:
        reporter = NoticeReporter()  # no progress bar off a terminal, but notices still matter
    try:
        try:
            settings = get_settings(download_dir=output, connections=connections, cookies_file=cookies)
            route = resolve(source, None if kind is KindOption.AUTO else Kind(kind.value), plugin=plugin)
            for note in take_notices():  # e.g. media detection skipped because Docker is missing
                reporter.notice(note)
            use: Path | None = None
            if settings.cookies_file is not None and route_takes_cookies(route):
                # only media and plugins with the cookies permission read them: only such a job is stopped by a bad
                # file or warns about its mode (a check, an HTTP or a torrent job never gets them)
                checked = check_cookies(settings.cookies_file)
                if checked.warning:
                    reporter.notice(checked.warning)
                use = checked.path
            elif settings.cookies_file is not None and (skipped := cookies_skipped(route)):
                reporter.notice(skipped)  # silent with --quiet
            dest = writable_dir(settings.download_dir)
            job = Job(source, dest, settings.connections, fmt, playlist, seed, insecure, use)
            paths = asyncio.run(engine_for(route).run(job, reporter))
        except KeyboardInterrupt:
            raise _fail("interrupted; partial data kept so the next run can resume", INTERRUPTED) from None
        except PartialDownload as e:
            for path in e.paths:
                typer.echo(str(path))
            raise _fail(str(e), e.exit_code) from None
        except AnydmError as e:
            raise _fail(str(e), e.exit_code) from None
        finally:
            close = getattr(reporter, "close", None)
            if close is not None:
                close()
    except KeyboardInterrupt:  # a second Ctrl-C while cleaning up
        raise typer.Exit(INTERRUPTED) from None
    for path in paths:
        typer.echo(str(path))
