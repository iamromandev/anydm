"""Which installed plugin handles a link: each plugin that has `check` is asked in order, the first yes wins."""

from collections.abc import Callable

from src.core.errors import DownloadError
from src.plugin.contract import clean_text
from src.plugin.model import Plugin
from src.plugin.runner import PluginRunner

CHECK_TIMEOUT = 10.0  # one plugin's `check`: seconds from the start of its container to its answer


def _ask(plugin: Plugin, url: str, runner: PluginRunner, notice: Callable[[str], None]) -> bool | None:
    """What `plugin` says about `url`. A plugin that fails, times out or gives no clear answer is skipped, with one
    notice, and gives None: not an answer, so not one to remember. The image is the pinned one, pulled by that
    digest first when it is missing."""
    try:
        runner.reap_once()
        runner.ensure_plugin_image(plugin.image, notice)
        lines = runner.call(plugin, "check", url, CHECK_TIMEOUT)
    except DownloadError as e:
        notice(f"skipped plugin {plugin.name} for this link: {e}")
        return None
    for line in lines:
        if line["type"] == "error":
            reason = clean_text(line.get("message")) or "it reported an error"
            notice(f"skipped plugin {plugin.name} for this link: {reason}")
            return None
        if line["type"] == "supported" and isinstance(line.get("value"), bool):
            return line["value"]
    notice(f"skipped plugin {plugin.name} for this link: it gave no answer")
    return None


def pick(
    url: str, plugins: list[Plugin], runner: PluginRunner, notice: Callable[[str], None],
    *, need: str = "download", cached_only: bool = False, skipped: str = "plugin",
) -> str | None:
    """The name of the first plugin (in the order given) that has the capability `need` ("download" or "info") and
    whose `check` says yes for `url`, or None. A plugin that cannot do what is needed is not asked: it would claim
    a link it then cannot serve.

    Each real answer is remembered per (plugin, url) for the life of `runner`: it is not asked twice. A check that
    failed is not an answer and is asked again next time. With `cached_only` only what is remembered is read and
    docker is never started (for the event loop). Without Docker the plugins are skipped with one notice
    (`skipped` names what is skipped in it)."""
    answers = runner.memo("pick")
    for plugin in plugins:
        if "check" not in plugin.capabilities or need not in plugin.capabilities:
            continue  # without `check` a plugin is only reachable with --plugin
        key = (plugin.name, plugin.image, url)
        answer = answers.get(key)
        if answer is None:
            if cached_only:
                continue
            if not runner.available():
                notice(f"{skipped} detection skipped: Docker is not available (see anydm doctor)")
                return None
            answer = _ask(plugin, url, runner, notice)
            if answer is not None:
                answers[key] = answer
        if answer:
            return plugin.name
    return None
