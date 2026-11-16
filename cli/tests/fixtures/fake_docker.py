#!/usr/bin/env python3
"""A stand-in for the `docker` command. FAKE_DOCKER_SCRIPT names a JSON file that says what it does:

  log        file that gets one JSON argv per line (every call)
  version    "ok" (default), "fail" or "hang"
  image      true (default) or false: `image inspect` result
  pull       "ok" (default), "fail" or "hang"
  check      true/false: the answer a `check` container prints (default false)
  check_lines  what a `check` container prints instead of hello and the answer (same format as `lines`)
  check_exit   exit code of a `check` container (default 0)
  check_stderr what a `check` container writes to stderr
  check_hang   true: a `check` container never ends by itself (it ends when `rm -f` is called for its name)
  lines      what a `download` container prints, one entry per line (str as is, anything else as JSON)
  exit       exit code of a `download` container (default 0)
  stderr     what a `download` container writes to stderr
  hang       true: a `download` container keeps running until `kill` is called for its name
  ignore_kill  true: it keeps running even after `kill` (only `rm -f` ends it)
  start_delay  seconds before a `download` container exists; `kill` and `rm` for it fail ("No such container") until then
  ps         lines `docker ps` prints
"""

import json
import os
import sys
import time
from pathlib import Path

cfg = json.loads(Path(os.environ["FAKE_DOCKER_SCRIPT"]).read_text())
args = sys.argv[1:]
log = cfg.get("log")
if log:
    with open(log, "a") as f:
        f.write(json.dumps(args) + "\n")


def marker(name: str, what: str) -> Path:
    return Path(f"{log}.{what}.{name}")


def option(flag: str) -> str | None:
    return args[args.index(flag) + 1] if flag in args else None


cmd = args[0] if args else ""
if cmd == "version":
    mode = cfg.get("version", "ok")
    if mode == "hang":
        time.sleep(30)
    if mode == "fail":
        print("Cannot connect to the Docker daemon", file=sys.stderr)
        sys.exit(1)
    print("27.0.0")
elif cmd == "image":
    sys.exit(0 if cfg.get("image", True) else 1)
elif cmd == "pull":
    if cfg.get("pull", "ok") == "hang":
        time.sleep(30)
    if cfg.get("pull", "ok") == "fail":
        print("pull access denied", file=sys.stderr)
        sys.exit(1)
elif cmd == "ps":
    for line in cfg.get("ps", []):
        print(line)
elif cmd in ("kill", "rm"):
    if cfg.get("start_delay") and not marker(args[-1], "created").exists():
        print(f"Error response from daemon: No such container: {args[-1]}", file=sys.stderr)
        sys.exit(1)
    marker(args[-1], "killed" if cmd == "kill" else "removed").write_text("")
elif cmd == "run":
    name = option("--name") or ""
    if cfg.get("start_delay"):
        time.sleep(cfg["start_delay"])
    marker(name, "created").write_text("")
    if "check" in args:
        if cfg.get("check_lines") is not None:
            for line in cfg["check_lines"]:
                print(line if isinstance(line, str) else json.dumps(line), flush=True)
        else:
            print(json.dumps({"type": "hello", "protocol": 1}), flush=True)
            print(json.dumps({"type": "supported", "value": cfg.get("check", False)}), flush=True)
        if cfg.get("check_stderr"):
            print(cfg["check_stderr"], file=sys.stderr, flush=True)
        while cfg.get("check_hang") and not marker(name, "removed").exists():
            time.sleep(0.02)
        sys.exit(cfg.get("check_exit", 0))
    for line in cfg.get("lines", []):
        print(line if isinstance(line, str) else json.dumps(line), flush=True)
    if cfg.get("stderr"):
        print(cfg["stderr"], file=sys.stderr, flush=True)
    if cfg.get("hang"):
        while not marker(name, "removed").exists():
            if marker(name, "killed").exists() and not cfg.get("ignore_kill"):
                sys.exit(130)
            time.sleep(0.02)
        sys.exit(137)
    sys.exit(cfg.get("exit", 0))
