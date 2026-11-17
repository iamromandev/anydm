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
  inspect    what `docker image inspect --format ...` prints (default: no digests and an id)
  manifest_*, info_*  like check_*: lines, exit, stderr, hang for a `manifest` or an `info` container
  ps         lines `docker ps` prints
  rmi        "ok" (default) or "fail": what `docker rmi` does
  plugins    the plugin table: {image reference: {...}}. A container (or an `image inspect`, `pull`) for an image in
             the table uses these keys instead of the top-level ones, so one fake acts as several plugins:
               manifest     a dict: a `manifest` container prints hello and {"type": "manifest", **manifest}
                            (manifest_lines, manifest_exit, ... still win when set)
               check        true/false, or {url: true/false} (an unlisted url is false)
               lines, exit, stderr, hang, info_lines, check_lines, ...   as above
               present      true (default) or false: whether the image is in the local store; a `pull` of it
                            makes it present
               inspect      what `image inspect --format ...` prints for it (default: its own reference as the only
                            digest when it is named by digest, else no digests; and an id)
               pulled_inspect  the same, once it was pulled
               pull         "ok" (default), "fail" or "hang"
"""

import json
import os
import re
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
table = cfg.get("plugins", {})
image_arg = args[-1] if args else ""  # `image inspect`, `pull` and `rmi` take the image last
if cmd == "run" and "--" in args and len(args) > args.index("--") + 1:
    image_arg = args[args.index("--") + 1]
entry = table.get(image_arg)
if entry is not None:  # an image of the plugin table: its keys win over the top-level ones
    cfg = {**cfg, **entry}


def pulled_marker() -> Path:
    return Path(f"{log}.pulled.{re.sub(r'[^A-Za-z0-9]', '_', image_arg)}")


if cmd == "version":
    mode = cfg.get("version", "ok")
    if mode == "hang":
        time.sleep(30)
    if mode == "fail":
        print("Cannot connect to the Docker daemon", file=sys.stderr)
        sys.exit(1)
    print("27.0.0")
elif cmd == "image":
    pulled = entry is not None and pulled_marker().exists()
    present = cfg.get("present", True) if entry is not None else cfg.get("image", True)
    present = present or pulled
    if present and "--format" in args:
        # an image of the table that is named by digest carries that digest, unless `inspect` says otherwise
        carried = f'["{image_arg}"] sha256:' + "0" * 64 if entry is not None and "@sha256:" in image_arg else None
        print(cfg.get("pulled_inspect", cfg.get("inspect")) if pulled
              else cfg.get("inspect", carried or "[] sha256:" + "0" * 64))
    sys.exit(0 if present else 1)
elif cmd == "pull":
    if cfg.get("pull", "ok") == "hang":
        time.sleep(30)
    if cfg.get("pull", "ok") == "fail":
        print("pull access denied", file=sys.stderr)
        sys.exit(1)
    if entry is not None:
        pulled_marker().write_text("")
elif cmd == "rmi":
    if cfg.get("rmi", "ok") == "fail":
        print("Error response from daemon: conflict: image is being used", file=sys.stderr)
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
    sub = args[args.index("--") + 2] if "--" in args and len(args) > args.index("--") + 2 else ""
    url = next((a.removeprefix("--url=") for a in args if a.startswith("--url=")), "")
    if sub in ("check", "manifest", "info"):
        if cfg.get(f"{sub}_lines") is not None:
            for line in cfg[f"{sub}_lines"]:
                print(line if isinstance(line, str) else json.dumps(line), flush=True)
        elif sub == "check":
            answer = cfg.get("check", False)
            if isinstance(answer, dict):
                answer = answer.get(url, False)
            print(json.dumps({"type": "hello", "protocol": 3}), flush=True)
            print(json.dumps({"type": "supported", "value": answer}), flush=True)
        elif sub == "manifest" and isinstance(cfg.get("manifest"), dict):
            print(json.dumps({"type": "hello", "protocol": 3}), flush=True)
            print(json.dumps({"type": "manifest", **cfg["manifest"]}), flush=True)
        else:
            print(json.dumps({"type": "hello", "protocol": 3}), flush=True)
        if cfg.get(f"{sub}_stderr"):
            print(cfg[f"{sub}_stderr"], file=sys.stderr, flush=True)
        while cfg.get(f"{sub}_hang") and not marker(name, "removed").exists():
            time.sleep(0.02)
        sys.exit(cfg.get(f"{sub}_exit", 0))
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
