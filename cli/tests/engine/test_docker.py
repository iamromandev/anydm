import asyncio
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.engine import docker
from src.engine.docker import (
    PID_LABEL,
    PROCESS_TOKEN,
    START_LABEL,
    TOKEN_LABEL,
    DockerRunner,
    DockerUnavailable,
    in_thread,
    own_start,
    process_start,
)

from tests.conftest import FakeDocker
from tests.engine.test_media import Collect

IMAGE = "anydm-media:0.0.1"


@pytest.fixture
def runner(fake_docker: FakeDocker) -> DockerRunner:
    return DockerRunner(os.environ["ANYDM_DOCKER"], IMAGE)


def dead_pid() -> int:
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    return proc.pid


def test_available_when_the_daemon_answers(runner: DockerRunner) -> None:
    assert runner.available() is True


def test_unavailable_when_the_daemon_fails(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    assert runner.available() is False


def test_unavailable_when_the_daemon_does_not_answer_in_time(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="hang")
    assert runner.available(timeout=0.3) is False


def test_unavailable_when_the_command_is_missing(tmp_path: Path) -> None:
    assert DockerRunner(str(tmp_path / "nope"), IMAGE).available() is False


def test_availability_is_asked_once(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    runner.available()
    runner.available()
    assert fake_docker.commands().count("version") == 1


def test_image_present_follows_image_inspect(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    assert runner.image_present() is True
    fake_docker.set(image=False)
    assert runner.image_present() is False
    assert ["image", "inspect", IMAGE] in fake_docker.calls()


async def test_ensure_image_does_nothing_when_present(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    await runner.ensure_image(Collect())
    assert "pull" not in fake_docker.commands()


async def test_a_missing_local_image_is_never_pulled(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(image=False)
    with pytest.raises(DownloadError, match="make build-media"):
        await runner.ensure_image(Collect())
    assert "pull" not in fake_docker.commands()


async def test_a_missing_registry_image_is_pulled_with_a_notice(fake_docker: FakeDocker) -> None:
    ref = "ghcr.io/x/anydm-media:1"
    runner = DockerRunner(os.environ["ANYDM_DOCKER"], ref)
    fake_docker.set(image=False)
    report = Collect()
    await runner.ensure_image(report)
    assert ["pull", "--", ref] in fake_docker.calls()
    assert any(ref in n for n in report.notices)


async def test_a_failed_pull_names_the_image_and_the_reason(fake_docker: FakeDocker) -> None:
    ref = "ghcr.io/x/anydm-media:1"
    runner = DockerRunner(os.environ["ANYDM_DOCKER"], ref)
    fake_docker.set(image=False, pull="fail")
    with pytest.raises(DownloadError, match=r"ghcr.io/x/anydm-media:1.*pull access denied"):
        await runner.ensure_image(Collect())


@pytest.mark.parametrize("answer", [True, False])
def test_supported_is_the_containers_answer(runner: DockerRunner, fake_docker: FakeDocker, answer: bool) -> None:
    fake_docker.set(check=answer)
    assert runner.supported("https://youtu.be/x") is answer


def test_supported_is_cached_per_url(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    runner.supported("https://a/1")
    runner.supported("https://a/1")
    runner.supported("https://a/2")
    assert [c for c in fake_docker.calls() if c[0] == "run"].__len__() == 2


def test_the_image_is_inspected_once_for_many_checks(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    runner.supported("https://a/1")
    runner.supported("https://a/2")
    assert fake_docker.commands().count("image") == 1


def test_supported_raises_when_docker_is_unavailable(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    with pytest.raises(DockerUnavailable):
        runner.supported("https://a/1")


def test_supported_raises_when_the_local_image_is_missing(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(image=False)
    with pytest.raises(DockerUnavailable, match="make build-media"):
        runner.supported("https://a/1")


def test_supported_passes_the_url_as_one_argument_after_the_image(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    url = "https://a/x; rm -rf / $(id)"
    runner.supported(url)
    [call] = [c for c in fake_docker.calls() if c[0] == "run"]
    assert call[-5:] == [IMAGE, "check", "--protocol", "2", f"--url={url}"]
    assert call[call.index(IMAGE) - 1] == "--"


def test_base_args_are_locked_down(runner: DockerRunner, tmp_path: Path) -> None:
    args = runner.base_args(tmp_path)
    assert args[0] == "run" and "--rm" in args
    assert "--privileged" not in args
    assert args.count("-v") == 1
    assert args[args.index("-v") + 1] == f"{tmp_path}:/downloads"
    assert args[args.index("--user") + 1] == f"{os.getuid()}:{os.getgid()}"
    assert [args[i + 1] for i, a in enumerate(args) if a == "-e"] == ["HOME=/tmp"]
    assert "anydm=media" in args
    assert not any(a.startswith(("--env", "--env-file", "--volume", "--mount")) for a in args)


def test_base_args_end_with_double_dash_so_the_image_cannot_be_an_option(runner: DockerRunner) -> None:
    assert runner.base_args(None)[-1] == "--"


def test_containers_are_labelled_with_the_token_pid_and_start_time_not_the_hostname(runner: DockerRunner) -> None:
    args = runner.base_args(None)
    labels = {args[i + 1].partition("=")[0]: args[i + 1].partition("=")[2] for i, a in enumerate(args) if a == "--label"}
    assert labels[TOKEN_LABEL] == PROCESS_TOKEN
    assert labels[PID_LABEL] == str(os.getpid())
    assert labels[START_LABEL] == own_start()
    assert set(labels) == {"anydm", TOKEN_LABEL, PID_LABEL, START_LABEL}


def test_the_token_differs_between_processes() -> None:
    other = subprocess.run(
        [sys.executable, "-c", "from src.engine.docker import PROCESS_TOKEN as t; print(t)"],
        capture_output=True, text=True, check=True, cwd=Path(__file__).parents[2],
    ).stdout.strip()
    assert other and other != PROCESS_TOKEN


def test_a_process_start_is_stable_and_differs_between_processes() -> None:
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
    try:
        assert process_start(child.pid) == process_start(child.pid) != ""
    finally:
        child.kill()
        child.wait()
    assert process_start(child.pid) == ""


def test_as_root_no_container_is_started(runner: DockerRunner, monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(os, "getuid", lambda: 0)
    with pytest.raises(DockerUnavailable, match="root"):
        runner.base_args(tmp_path)


COOKIES_TARGET = "/run/anydm/cookies.txt"


def mounts(args: list[str]) -> list[str]:
    return [args[i + 1] for i, a in enumerate(args) if a == "--mount"]


def test_the_protocol_is_2() -> None:
    assert docker.PROTOCOL == 2
    assert docker.COOKIES_MOUNT == COOKIES_TARGET


def test_cookies_are_mounted_read_only_outside_the_output_folder(runner: DockerRunner, tmp_path: Path) -> None:
    cookies = tmp_path / "c.txt"
    args = runner.base_args(tmp_path, cookies)
    assert mounts(args) == [f"type=bind,src={cookies},dst={COOKIES_TARGET},readonly"]
    assert args.count("-v") == 1  # the output folder is the only other mount
    assert args[args.index("-v") + 1] == f"{tmp_path}:/downloads"
    assert args[-1] == "--"


def test_cookies_can_be_the_only_mount(runner: DockerRunner, tmp_path: Path) -> None:
    args = runner.base_args(None, tmp_path / "c.txt")
    assert "-v" not in args
    assert len(mounts(args)) == 1


def test_no_cookies_means_no_mount(runner: DockerRunner, tmp_path: Path) -> None:
    assert mounts(runner.base_args(tmp_path)) == []
    assert mounts(runner.base_args(tmp_path, None)) == []


def test_a_cookies_path_with_a_colon_or_spaces_is_fine_with_mount(runner: DockerRunner, tmp_path: Path) -> None:
    cookies = tmp_path / "my cookies: 1.txt"
    assert mounts(runner.base_args(None, cookies)) == [f"type=bind,src={cookies},dst={COOKIES_TARGET},readonly"]


@pytest.mark.parametrize("name", ["a,b.txt", "x,readonly=false.txt", "new\nline.txt", 'q"uote.txt', "del\x7f.txt"])
def test_a_cookies_path_that_would_break_the_mount_option_is_refused(
    runner: DockerRunner, tmp_path: Path, name: str
) -> None:
    with pytest.raises(DownloadError, match="cookies"):
        runner.base_args(None, tmp_path / name)


def test_base_args_without_a_folder_mount_nothing(runner: DockerRunner) -> None:
    assert "-v" not in runner.base_args(None)


def test_every_container_gets_its_own_name(runner: DockerRunner) -> None:
    def name(args: list[str]) -> str:
        return args[args.index("--name") + 1]

    assert name(runner.base_args(None)) != name(runner.base_args(None))


def test_a_folder_with_a_colon_is_refused(runner: DockerRunner, tmp_path: Path) -> None:
    with pytest.raises(DownloadError, match="':'"):
        runner.base_args(tmp_path / "a:b")


def row(cid: str, pid: object, started: str, token: str = "someone-else") -> str:
    return "\t".join([cid, str(pid), started, token])


def labelled(fake_docker: FakeDocker, *lines: str) -> None:
    fake_docker.set(ps=list(lines))


def sleeper() -> subprocess.Popen[bytes]:
    return subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])


def test_reap_removes_a_container_whose_parent_is_dead(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    labelled(fake_docker, row("dead1", dead_pid(), "whenever"))
    runner.reap_stale()
    assert ["rm", "-f", "dead1"] in fake_docker.calls()
    [ps] = [c for c in fake_docker.calls() if c[0] == "ps"]
    assert ps[ps.index("--filter") + 1] == "label=anydm=media"


def test_reap_keeps_a_container_whose_parent_is_alive_with_the_same_start(
    runner: DockerRunner, fake_docker: FakeDocker
) -> None:
    other = sleeper()
    try:
        labelled(fake_docker, row("live1", other.pid, process_start(other.pid)))
        runner.reap_stale()
    finally:
        other.kill()
        other.wait()
    assert "rm" not in fake_docker.commands()


def test_reap_removes_a_container_whose_pid_was_reused_by_another_process(
    runner: DockerRunner, fake_docker: FakeDocker
) -> None:
    other = sleeper()
    try:
        labelled(fake_docker, row("reused1", other.pid, "a different start time"))
        runner.reap_stale()
    finally:
        other.kill()
        other.wait()
    assert ["rm", "-f", "reused1"] in fake_docker.calls()


def test_reap_never_touches_a_container_of_this_process(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    labelled(fake_docker, row("mine1", dead_pid(), "x", token=PROCESS_TOKEN))
    runner.reap_stale()
    assert "rm" not in fake_docker.commands()


def test_reap_keeps_containers_without_a_usable_owner(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    labelled(fake_docker, "bare1", "junk1\tnonsense\tx\ty", row("empty1", "", ""))
    runner.reap_stale()
    assert "rm" not in fake_docker.commands()


def test_reap_does_nothing_without_docker(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(version="fail")
    runner.reap_stale()
    assert "ps" not in fake_docker.commands()


def test_the_first_check_reaps_stale_containers_once(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    labelled(fake_docker, row("dead1", dead_pid(), "x"))
    runner.supported("https://a/1")
    runner.supported("https://a/2")
    cmds = fake_docker.commands()
    assert cmds.count("ps") == 1
    assert cmds.index("ps") < cmds.index("run")
    assert ["rm", "-f", "dead1"] in fake_docker.calls()


def test_a_check_that_gives_no_answer_is_remembered_per_url(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    fake_docker.set(check_lines=[], check_exit=1, check_stderr="boom")
    for _ in range(2):
        with pytest.raises(DockerUnavailable, match="boom"):
            runner.supported("https://a/1")
    assert [c for c in fake_docker.calls() if c[0] == "run"].__len__() == 1


def test_a_failed_pull_inside_supported_is_docker_unavailable(fake_docker: FakeDocker) -> None:
    ref = "ghcr.io/x/anydm-media:1"
    fake_docker.set(image=False, pull="fail")
    with pytest.raises(DockerUnavailable, match=r"cannot pull.*pull access denied"):
        DockerRunner(os.environ["ANYDM_DOCKER"], ref).supported("https://a/1")


def test_a_pull_that_hangs_inside_supported_is_docker_unavailable(
    fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(docker, "PULL_TIMEOUT", 0.3)
    fake_docker.set(image=False, pull="hang")
    with pytest.raises(DockerUnavailable, match="cannot pull"):
        DockerRunner(os.environ["ANYDM_DOCKER"], "ghcr.io/x/anydm-media:1").supported("https://a/1")


def test_a_command_that_cannot_run_during_the_pull_is_docker_unavailable(
    fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake_docker.set(image=False)
    real = DockerRunner._run

    def run(self: DockerRunner, *args: str, timeout: float) -> subprocess.CompletedProcess[str]:
        if args[0] == "pull":
            raise OSError("exec format error")
        return real(self, *args, timeout=timeout)

    monkeypatch.setattr(DockerRunner, "_run", run)
    with pytest.raises(DockerUnavailable, match="cannot pull"):
        DockerRunner(os.environ["ANYDM_DOCKER"], "ghcr.io/x/anydm-media:1").supported("https://a/1")


def test_a_pull_inside_supported_prints_a_notice(fake_docker: FakeDocker) -> None:
    ref = "ghcr.io/x/anydm-media:1"
    fake_docker.set(image=False)
    notes: list[str] = []
    DockerRunner(os.environ["ANYDM_DOCKER"], ref).supported("https://a/1", notes.append)
    assert ["pull", "--", ref] in fake_docker.calls()
    assert len(notes) == 1 and ref in notes[0]


def test_a_check_that_times_out_has_its_container_removed(
    runner: DockerRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(docker, "CHECK_TIMEOUT", 0.4)
    fake_docker.set(check_hang=True)
    with pytest.raises(DockerUnavailable, match="timed out"):
        runner.supported("https://a/1")
    [call] = [c for c in fake_docker.calls() if c[0] == "run"]
    assert ["rm", "-f", call[call.index("--name") + 1]] in fake_docker.calls()


@pytest.mark.parametrize("lines", [[{"type": "hello", "protocol": 1}, {"type": "supported", "value": True}],
                                   [{"type": "supported", "value": True}]])
def test_a_protocol_mismatch_is_a_plain_error_not_a_skipped_check(
    runner: DockerRunner, fake_docker: FakeDocker, lines: list[object]
) -> None:
    fake_docker.set(check_lines=lines)
    for _ in range(2):  # the second answer comes from the cache and is the same error
        with pytest.raises(DownloadError, match="update the media image") as ei:
            runner.supported("https://a/1")
        assert not isinstance(ei.value, DockerUnavailable)
    assert [c for c in fake_docker.calls() if c[0] == "run"].__len__() == 1


def test_cached_supported_only_returns_what_was_asked(runner: DockerRunner, fake_docker: FakeDocker) -> None:
    assert runner.cached_supported("https://a/1") is None
    runner.supported("https://a/1")
    assert runner.cached_supported("https://a/1") is False
    assert [c for c in fake_docker.calls() if c[0] == "run"].__len__() == 1


def test_a_missing_docker_is_asked_again_after_30_seconds(
    runner: DockerRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    now = [100.0]
    monkeypatch.setattr(docker, "_clock", lambda: now[0])
    fake_docker.set(version="fail")
    assert runner.available() is False
    now[0] += 29
    assert runner.available() is False
    assert fake_docker.commands().count("version") == 1
    fake_docker.set(version="ok")
    now[0] += 2
    assert runner.available() is True
    assert fake_docker.commands().count("version") == 2


def test_a_working_docker_is_not_asked_again(
    runner: DockerRunner, fake_docker: FakeDocker, monkeypatch: pytest.MonkeyPatch
) -> None:
    now = [100.0]
    monkeypatch.setattr(docker, "_clock", lambda: now[0])
    assert runner.available() is True
    now[0] += 3600
    assert runner.available() is True
    assert fake_docker.commands().count("version") == 1


async def test_in_thread_returns_the_result_and_raises_the_error() -> None:
    assert await in_thread(lambda a, b: a + b, 1, 2) == 3
    with pytest.raises(ValueError, match="x"):
        await in_thread(lambda: (_ for _ in ()).throw(ValueError("x")))


def test_a_cancelled_in_thread_call_does_not_hold_up_the_interpreter() -> None:
    code = (
        "import asyncio, time\n"
        "from src.engine.docker import in_thread\n"
        "async def main():\n"
        "    t = asyncio.create_task(in_thread(time.sleep, 30))\n"
        "    await asyncio.sleep(0.1)\n"
        "    t.cancel()\n"
        "asyncio.run(main())\n"
    )
    start = time.monotonic()
    subprocess.run([sys.executable, "-c", code], cwd=Path(__file__).parents[2], check=True, timeout=20)
    assert time.monotonic() - start < 5


async def test_a_cancelled_availability_check_does_not_delay_the_end_of_the_program(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    code = (
        "import asyncio, os\n"
        "from pathlib import Path\n"
        "from src.engine import Job\n"
        "from src.engine.docker import DockerRunner\n"
        "from src.engine.media_docker import DockerMediaEngine\n"
        "from src.core.progress import NullReporter\n"
        "async def main():\n"
        "    eng = DockerMediaEngine(DockerRunner(os.environ['ANYDM_DOCKER'], 'anydm-media:1'))\n"
        "    job = Job('http://x/a', Path('.'), 1, None, False, False, False)\n"
        "    t = asyncio.create_task(eng.run(job, NullReporter()))\n"
        "    await asyncio.sleep(0.3)\n"
        "    t.cancel()\n"
        "asyncio.run(main())\n"
    )
    fake_docker.set(version="hang")
    start = time.monotonic()
    proc = await asyncio.create_subprocess_exec(sys.executable, "-c", code, cwd=Path(__file__).parents[2])
    await asyncio.wait_for(proc.wait(), 20)
    assert time.monotonic() - start < 2.5
