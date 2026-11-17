# Writing an anydm plugin

A plugin is a Docker image, in any language, that speaks a small JSON-lines contract. anydm installs it by digest,
asks you to approve its permissions, and then asks it, in order, whether it can handle a link. The built-in `media`
plugin (yt-dlp) speaks the same contract and is always asked last. A working example in Go is in
[`plugins/example`](../plugins/example) (`make build-example-plugin`, then `anydm plugin add anydm-example:latest`).

This document describes **protocol 3**. anydm supports exactly one protocol: a plugin that answers with another is
refused with "plugin NAME speaks protocol X; anydm needs Y".

## Running a plugin

anydm runs one throwaway container per call:

```
docker run --rm ... IMAGE <command> --protocol 3 [--url=URL] [--format=F] [--playlist] [--insecure] [--cookies]
```

Your image's entrypoint receives the command (`manifest`, `check`, `download` or `info`) as its first argument.
`--protocol N` is two arguments (the number is the next one); every other value is one argument, `--name=value`, so a
URL that starts with `-` is never read as an option. `--playlist`, `--insecure` and `--cookies` are bare flags. Ignore
any option you do not understand: `--format` and `--playlist` are passed through from `anydm get` and a plugin may
disregard them.

| Command | Arguments | Must print |
|---|---|---|
| `manifest` | `--protocol` | `hello`, then `manifest` |
| `check` | `--protocol`, `--url` | `hello`, then `supported` |
| `download` | `--protocol`, `--url`, optional `--format`, `--playlist`, `--insecure`, `--cookies` | `hello`, then any number of `progress` and `notice`, then `done` or `error` |
| `info` | `--protocol`, `--url`, optional `--format`, `--playlist`, `--cookies` | `hello`, then `info` (or `error`) |

A plugin may implement only some commands, and says which in its manifest (`capabilities`). A plugin without `check`
is never asked about a link: it is only used with `--plugin NAME`.

## Output: JSON lines on stdout

One JSON object per line, UTF-8, each with a `type`. The **first line must be `hello`**. Anything on stderr is ignored,
except that its last few kilobytes are shown when the plugin fails without an `error` line.

```json
{"type":"hello","protocol":3}
```

`manifest` (answer to `manifest`):

```json
{"type":"manifest","name":"example","version":"0.1.0","capabilities":["check","download","info"],"permissions":["network"],"description":"What it does"}
```

`supported` (answer to `check`; `value` must be a boolean):

```json
{"type":"supported","value":true}
```

`progress` (during `download`; `done` and `total` are bytes, `total` may be `null`, `speed` is bytes per second, `name`
is the file being written; about ten a second is plenty and these lines are not counted against the line limit):

```json
{"type":"progress","done":1048576,"total":5242880,"speed":2097152,"name":"abc.bin"}
```

`notice` (a message shown to the user):

```json
{"type":"notice","message":"logged in as a guest"}
```

`info` (answer to `info`; every field is optional):

```json
{"type":"info","title":"abc","size":5242880,"formats":[{"id":"hd","ext":"mp4","note":"1080p"}],"items":[{"title":"part 1","url":"https://example.com/example/1"}]}
```

`done` (the last line of a successful `download`; `paths` are the files saved, as seen in the container):

```json
{"type":"done","paths":["/downloads/abc.bin"]}
```

`error` (the last line of a failed call; `partial` lists the files kept so that a later run can resume them):

```json
{"type":"error","message":"the site answered 404 Not Found","partial":[]}
```

Unknown fields, and lines of an unknown `type`, are ignored, so a newer plugin can say more than this anydm
understands. Exit 0 after `done` (or `manifest`, `supported`, `info`) and non-zero after `error`.

### Limits and trust

Everything a plugin prints is untrusted input and is bounded. A call fails cleanly (and anydm never crashes) when:

- a line is longer than 1 MB, or is not JSON (such a line is skipped, but it counts);
- the plugin prints more than 10 000 lines (`progress` lines excepted);
- the plugin prints no `hello`, or one with another protocol, or exits 0 without a result line;
- `done` has no `paths` ("plugin NAME saved nothing"), or a path is outside `/downloads`.

Text that reaches the screen (names, titles, messages, notices) has control and bidirectional characters stripped
and is cut at 500 characters. At most 500 `formats` and 500 `items` of one `info` line are read. Time limits: `check`
10 seconds, `manifest` 30 seconds, `info` 120 seconds (the container is removed when it runs over); `download` has none
and is cancelled with Ctrl-C (or `c` in the TUI), which stops and removes the container within about two seconds.

## Files

`download` runs with the output folder mounted at `/downloads` (and nowhere else): write there, and report paths
under it. anydm translates them back to host paths and refuses any that are outside the folder (for example with `..`). Only the paths you report are printed; other files you leave in the folder stay there. Write to a temporary
name such as `NAME.part` and rename when finished, so an interrupted download is not mistaken for a complete one. The
container runs as the caller's uid and gid, so the files are the caller's. `HOME` is `/tmp`; the root filesystem is the
image's own, so do not assume anything else is writable.

## The manifest

| Field | Rule |
|---|---|
| `name` | 2 to 32 characters: a lowercase letter, then lowercase letters, digits or `-`. `http`, `torrent` and `media` are reserved. |
| `version` | Free text, shown to the user. |
| `capabilities` | A non-empty list from `check`, `download`, `info`. |
| `permissions` | A list from `network`, `cookies` (may be empty). |
| `description` | Optional free text. |

`anydm plugin add` refuses a manifest with a bad name or an unknown capability or permission, writing nothing.

## Permissions and the sandbox

Permissions are what the user approved at `anydm plugin add`, kept in `plugins.toml` beside `config.toml` (mode 0600,
never read from `.env` or the environment). They are enforced from that approval, not from what the manifest says later.

- `network`: without it the container runs with `--network none`. With it, `download` and `info` get the default
  network. **`manifest` and `check` never get the network**, whatever was approved, so `check` must decide from the
  URL alone.
- `cookies`: when the user passes `--cookies FILE`, the file is mounted read-only at `/run/anydm/cookies.txt` for
  `download` and `info`, and `--cookies` is passed as a flag. Without this permission the file is not mounted and a
  notice says the cookies were not passed. Copy the file before using it if your tool wants to write to it.

A plugin with `network` can send anything it can see out of the container: the URL it was given and, with `cookies`,
the cookies too. Approve only plugins you trust.

Every container also gets: `--rm`, `--cap-drop ALL`, `--security-opt no-new-privileges`, the caller's `--user`, a
fresh name and `anydm` labels (so a container left behind by a killed anydm is removed on the next run), and no
environment variables except `HOME=/tmp`. Mounts are only `/downloads` (for `download`) and the cookies file.

## Installing and pinning

`anydm plugin add IMAGE` pulls the image if needed, resolves it to a digest, runs `manifest` (no network, no mounts,
30 seconds), shows the name, version, capabilities and permissions, and asks to confirm. The digest, `repo@sha256:...`
(or the image id of an image that only exists locally), is what is saved and what runs from then on; if the image is
gone locally anydm pulls that exact digest first. A tag is never run. `--yes` skips the question but **never approves a
new permission** for a name that is already installed: run again without `--yes` to approve. Without a terminal and
without `--yes`, `add` exits 2. A rebuilt image under the same tag is not picked up until it is added again.

Plugins are asked in the order of `anydm plugin list` (`anydm plugin order NAME...` changes it); the first to answer
`supported` with `true` handles the link. A plugin that fails, times out or gives no answer is skipped with a notice,
and that failure is not remembered. A plugin without the capability the command needs (`download` for `get`, `info` for
`info`) is not asked; `--plugin NAME` for one is a usage error (exit 2).

## Versioning

The protocol is one integer, sent as `--protocol N` and in `hello`. It changes only when a plugin written for the old
one would break. After upgrading anydm to a new protocol, rebuild the built-in media image with `make build-media` and
rebuild or update your plugins.
