import json
import sys
from collections.abc import Callable, Generator
from contextlib import contextmanager
from typing import Annotated

import typer

from src.core.errors import AnydmError, DownloadError, UsageError
from src.core.names import clean_display
from src.plugin.contract import clean_text
from src.plugin.model import Plugin, is_pinned

INTERRUPTED = 130
MANIFEST_TIMEOUT = 30.0  # seconds a plugin gets to print its manifest

app = typer.Typer(name="plugin", help="Install, list, remove and order plugins.", no_args_is_help=True)


def _interactive() -> bool:
    return sys.stdin.isatty()


@contextmanager
def _errors() -> Generator[None]:
    """Turn an anydm error into a message and its exit code, and Ctrl-C into 130."""
    try:
        yield
    except KeyboardInterrupt:
        typer.secho("error: interrupted", fg=typer.colors.RED, err=True)
        raise typer.Exit(INTERRUPTED) from None
    except AnydmError as e:
        typer.secho(f"error: {e}", fg=typer.colors.RED, err=True)
        raise typer.Exit(e.exit_code) from None


_ORDER = ("check", "download", "info", "network", "cookies")  # how capabilities and permissions are listed


def _sorted(values: frozenset[str]) -> list[str]:
    return sorted(values, key=_ORDER.index)


def _version(plugin: Plugin) -> str:
    return "built-in" if plugin.builtin else clean_text(plugin.version)


def _join(values: frozenset[str]) -> str:
    return ", ".join(_sorted(values)) or "nothing"


def _notice(message: str) -> None:
    typer.secho(message, err=True)


def _known(plugins: list[Plugin], name: str) -> Plugin:
    """The installed plugin `name`; UsageError (exit 2) when there is none, or for the built-in media."""
    if name == "media":
        raise UsageError("media is built in; it cannot be removed or ordered")
    found = next((p for p in plugins if p.name == name), None)
    if found is None:
        raise UsageError(f"no plugin named {name!r}; see anydm plugin list")
    return found


def _store(change: Callable[[list[Plugin]], list[Plugin]]) -> None:
    """Apply `change` to the state as it is now and save the result. The state is read again here, right before the
    write, never reused from earlier: a command that waited (a pull, a question) must not write back a stale copy
    over what another anydm command saved meanwhile. A state that cannot be written is an error message (exit 1)
    and the old file stays."""
    from src.plugin import state

    updated = change(state.load())
    try:
        state.save(updated)
    except (ValueError, OSError) as e:  # UnicodeError is a ValueError
        reason = e.strerror if isinstance(e, OSError) and e.strerror else clean_display(str(e))
        raise DownloadError(f"cannot save the plugin state {state.state_path()}: {reason or type(e).__name__}") from e


def _runner():
    from src.engine import _docker_runner  # lazy: keeps --help fast

    runner = _docker_runner()
    if not runner.available():
        raise DownloadError("plugins need Docker; see anydm doctor")
    return runner


def _probe(runner, image: str):
    """The manifest of the plugin in `image`, read in a container with no network and no mounts. A manifest that
    breaks the rules is a UsageError; a plugin that fails or does not answer is a DownloadError."""
    from src.plugin.contract import ContractError, parse_manifest

    candidate = Plugin("candidate", image, "", frozenset({"check", "download", "info"}), frozenset())
    lines = runner.call(candidate, "manifest", None, MANIFEST_TIMEOUT)
    line = next((x for x in lines if x["type"] == "manifest"), None)
    if line is None:
        raise DownloadError("the image printed no manifest; is it an anydm plugin?")
    try:
        return parse_manifest(line)
    except ContractError as e:
        raise UsageError(str(e)) from e


@app.command()
def add(
    image: Annotated[str, typer.Argument(help="Image reference, e.g. ghcr.io/acme/anydm-pixiv:1.2")],
    yes: Annotated[bool, typer.Option("--yes", help="Approve the permissions without asking")] = False,
) -> None:
    """Install a plugin: pull its image, read its manifest, and ask before approving its permissions."""
    from src.config.settings import _IMAGE_REF
    from src.plugin import state
    from src.plugin.runner import pullable

    with _errors():
        if not _IMAGE_REF.fullmatch(image):
            raise UsageError(f"{image!r} is not an image reference")
        if not yes and not _interactive():
            raise UsageError("cannot ask for approval without a terminal; run again with --yes to approve")
        plugins = state.load()  # a corrupt file stops here, before anything is pulled
        runner = _runner()
        if not runner.image_present(image):
            if not pullable(image):
                raise DownloadError(f"image {image} not found; build or tag it first")
            runner.pull_image(image, _notice)
        pinned = runner.digest_of(image)
        if pinned is None:
            raise DownloadError(f"cannot find image {image}")
        if not is_pinned(pinned):
            raise DownloadError(f"cannot pin image {image} by digest: docker reports {clean_display(pinned)!r}")
        manifest = _probe(runner, pinned)  # the pinned image: what is approved is what will run

        typer.echo(f"{manifest.name} {manifest.version} — capabilities: {_join(manifest.capabilities)}"
                   f" — asks for: {_join(manifest.permissions)}")
        if manifest.description:
            typer.echo(manifest.description)
        typer.echo(f"image: {pinned}")
        old = next((p for p in plugins if p.name == manifest.name), None)
        if old is not None:
            typer.echo(f"{manifest.name} is already installed (version {old.version}, image {old.image})")
            added, dropped = manifest.permissions - old.permissions, old.permissions - manifest.permissions
            if added:
                typer.echo(f"now asks for: {_join(added)}")
            if dropped:
                typer.echo(f"no longer asks for: {_join(dropped)}")
            if not added and not dropped:
                typer.echo("same permissions as before")
            if yes and added:
                # --yes is for a first install and for updates that ask for no more than was approved
                raise UsageError(
                    f"{manifest.name} now asks for more permissions than you approved ({_join(added)}); "
                    "run again without --yes to approve them"
                )
        if not yes and not typer.confirm("Allow?", default=False, abort=False):
            typer.echo("not installed")
            raise typer.Exit(1)

        new = Plugin(manifest.name, pinned, manifest.version, manifest.capabilities, manifest.permissions)

        def apply(current: list[Plugin]) -> list[Plugin]:
            now = next((p for p in current if p.name == new.name), None)
            if now != old:  # what was approved was a change to `old`; it is not what is installed any more
                raise DownloadError(f"plugin {new.name} changed while you were answering; run the command again")
            return [new if p.name == new.name else p for p in current] if now else [*current, new]

        _store(apply)
        typer.echo(f"{'updated' if old else 'installed'} plugin {new.name}")


@app.command("list")
def list_(
    as_json: Annotated[bool, typer.Option("--json", help="Print a JSON list")] = False,
) -> None:
    """List the plugins in the order anydm asks them; media is built in and always last."""
    from src.config.settings import get_settings
    from src.plugin import state

    with _errors():
        plugins = state.routing(get_settings())
    if as_json:
        typer.echo(json.dumps([
            {"name": p.name, "version": _version(p), "capabilities": _sorted(p.capabilities),
             "permissions": _sorted(p.permissions), "image": p.image, "builtin": p.builtin}
            for p in plugins
        ]))
        return
    for p in plugins:
        caps, perms = ",".join(_sorted(p.capabilities)), ",".join(_sorted(p.permissions)) or "-"
        typer.echo(f"{p.name:<16}{_version(p):<12}{caps:<20}{perms:<16}{p.image}")


@app.command()
def remove(
    name: Annotated[str, typer.Argument(help="Name of the plugin")],
    image: Annotated[bool, typer.Option("--image", help="Also remove its image from Docker")] = False,
) -> None:
    """Remove a plugin."""
    from src.plugin import state

    with _errors():
        plugins = state.load()
        gone = _known(plugins, name)
        if image:  # first, so that a Docker that is down or refuses leaves the plugin installed, not half removed
            runner = _runner()
            users = [p.name for p in plugins if p.name != name and p.image == gone.image]
            if users:
                typer.echo(f"image {gone.image} kept: still used by {', '.join(users)}")
            elif not runner.image_present(gone.image):
                typer.echo(f"image {gone.image} is not present")
            else:
                try:
                    runner.remove_image(gone.image)
                except DownloadError as e:
                    raise DownloadError(f"{e}; plugin {name} was not removed") from e
                typer.echo(f"removed image {gone.image}")
        _store(lambda current: [p for p in current if p.name != name])
        typer.echo(f"removed plugin {name}")


@app.command()
def order(
    names: Annotated[list[str], typer.Argument(help="Plugin names, first asked first")],
) -> None:
    """Set the order plugins are asked; the ones not listed follow in their current order, and media is last."""
    from src.plugin import state

    with _errors():
        plugins = state.load()
        first = [_known(plugins, n) for n in dict.fromkeys(names)]
        listed = [p.name for p in first]
        _store(lambda current: [*(p for n in listed for p in current if p.name == n),
                                *(p for p in current if p.name not in listed)])
        typer.echo("order: " + ", ".join([*listed, "..."]))
