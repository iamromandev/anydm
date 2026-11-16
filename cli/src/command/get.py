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
) -> None:
    """Download SOURCE and print the saved paths."""
    from src.config.settings import get_settings  # lazy: keeps --help fast
    from src.core.progress import NoticeReporter, NullReporter, Reporter, RichReporter
    from src.engine import Job, Kind, engine_for, resolve

    reporter: Reporter
    if quiet:
        reporter = NullReporter()
    elif sys.stderr.isatty():
        reporter = RichReporter()
    else:
        reporter = NoticeReporter()  # no progress bar off a terminal, but notices still matter
    try:
        try:
            settings = get_settings(download_dir=output, connections=connections)
            resolved = resolve(source, None if kind is KindOption.AUTO else Kind(kind.value))
            dest = writable_dir(settings.download_dir)
            job = Job(source, dest, settings.connections, fmt, playlist, seed, insecure)
            paths = asyncio.run(engine_for(resolved).run(job, reporter))
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
