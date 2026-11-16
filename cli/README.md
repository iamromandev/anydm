# anydm CLI

`anydm get <url | magnet | file.torrent>` downloads the source and prints the saved paths. Run `make help` for the
development commands.

## Queue (TUI)

`anydm tui` opens a full-screen queue of HTTP, media and torrent downloads (needs a terminal of at least 80x24). Add
links, watch live progress, and cancel, retry or remove each download. It uses the same engines and the same partial
files as `anydm get`, so a download stopped in one can be resumed by the other.

| Key | Action |
|---|---|
| `a` | Add a link and a folder (default: `download_dir`) |
| `c` | Cancel the selected download (partial data is kept) |
| `r` | Retry a failed or cancelled download; it resumes |
| `d` | Remove a row from the list (never deletes files) |
| `o` | Open the folder of a finished download |
| `y` | Copy the path of a finished download |
| `q` / Ctrl-C | Quit; asks first while downloads run, then stops them and keeps their partial data |
| Up / Down | Move the selection |

At most `max_active` downloads run at once (1 to 10, default 3; `ANYDM_MAX_ACTIVE`, `.env` or `max_active` in
config.toml). The rest wait as `queued`. The queue is kept only while the app is open: nothing is saved between runs
and torrents are not seeded. Copying the path uses the terminal clipboard (OSC 52), which not every terminal supports,
so the path is also shown in a notification.

## Torrents

`anydm get file.torrent` (a URL of a .torrent file, or a `magnet:` link) uses a built-in, pure-Python BitTorrent
client with no extra dependencies.

- **Supported:** BitTorrent v1 single- and multi-file torrents, HTTP(S) and UDP trackers (announce lists, compact
  and IPv6 peers), resuming an interrupted download, BEP 47 padding files.
- **Magnet links:** `xt=urn:btih:` in 40-hex or 32-base32 form, `dn` and any number of `tr`. The torrent's metadata
  is fetched from peers (BEP 9/10, at most 16 MiB, verified against the infohash, then parsed like any .torrent
  file), with the progress phase `metadata`. A v2-only link (`urn:btmh`) or one without a valid infohash exits 2.
  The metadata is kept in the output folder as `<infohash>.torrent`, so running the same magnet again skips the
  metadata phase and resumes.
- **DHT (BEP 5, lookups only):** peers are also looked up in the DHT, always for magnets, and for a .torrent file
  that lists no trackers or whose trackers all fail. anydm only asks: it does not answer other nodes or announce
  itself. The bootstrap nodes are `router.bittorrent.com:6881`, `dht.transmissionbt.com:6881` and
  `router.utorrent.com:6881`; set `ANYDM_DHT_BOOTSTRAP=host:port,host:port` to use others, or set it to an empty
  value to turn the DHT off (a value with no valid `host:port` also turns it off, with a notice). Peers on local or
  private addresses are only believed from the DHT when `ANYDM_DHT_BOOTSTRAP` names nodes that are all local (for
  tests), never for the built-in names.
- **Private torrents and magnets (BEP 27):** a `.torrent` file that is private never touches the DHT. A *magnet*
  link does not say whether its torrent is private, so during the metadata phase the DHT is asked for its peers,
  which sends the **infohash to the public DHT**. Once the metadata shows the private flag, the DHT is off for the
  rest of the download. To keep a private torrent's infohash off the public DHT altogether, use the `.torrent` file,
  or set `ANYDM_DHT_BOOTSTRAP=` (empty) before using its magnet link.
- **Tracker URLs (from a magnet's `tr` or a .torrent) are hostile input:** a tracker whose host resolves to a loopback,
  private, link-local or otherwise non-public address is only contacted when the URL path ends with `/announce`;
  otherwise it is skipped with a notice. Set `ANYDM_ALLOW_LOCAL_TRACKERS=1` to allow any local tracker URL.
- **Seeding (`--seed`):** after the download completes, anydm keeps serving the files until Ctrl-C (which then exits 0
  and prints the paths, within about a second even if a peer has stopped reading or a tracker no longer answers; Ctrl-C
  during the download still exits 130). It listens on **all interfaces (`0.0.0.0`), TCP 6881** (any free port if that is
  taken), so anyone who can reach the machine can connect: use `--seed` only where that is fine. While the download
  itself runs nothing listens, and the trackers are told port 0. It announces to the trackers, serves magnet-link
  peers the torrent's metadata, and says nothing to a peer that asks for another torrent. Peers are hostile input:
  - It uploads to at most 4 peers at once. Interested peers wait in the order they asked; with others waiting, an
    unchoked peer gives up its slot after about 30 s, or about 60 s without a request, and goes to the back of the line.
  - Each peer may have up to 250 requests queued (the `reqq` it is told); extra requests are dropped, and a peer that
    keeps sending far more is hung up on. Every request is checked against the torrent before the disk is read, and
    the disk is read off the event loop.
  - At most 50 peers are served at once and 3 connections are taken from one address; a connection that has not
    handshaken within 10 s is dropped and uses no peer slot.
  - A peer that does not read for 30 s, lets more than 1 MiB pile up, or flips its interest over and over is
    disconnected.

  Without `--seed` it exits when the download completes.
- **Not supported yet:** BitTorrent **v2 and hybrid** torrents (they are refused with an error). uTP, encryption, PEX,
  web seeds and announcing to the DHT are out of scope for now.
- **Piece length** above 32 MiB is refused. **Free disk space** is checked before anything is created.
- **Existing files are never overwritten.** If a file the torrent would write already exists and is not a copy of
  this torrent's data (no matching piece, and no resume record from an earlier run), the torrent is saved under a
  new top-level name, `name (1)`, `name (2)`, and so on, and a notice says so. The choice is remembered in the
  `<infohash>.anydm.json` resume record, so a rerun resumes in the same place.
- **No peers or no answering tracker or DHT:** the client keeps waiting and prints `waiting for peers (N trackers,
  DHT, M peers tried)` every 30 seconds while it has no connections. It never gives up by itself; press Ctrl-C (exit code 130,
  partial data and the resume record are kept).
- Tracker, DHT and peer input is treated as hostile: addresses are validated, message sizes and counts are capped,
  peers that sit on requests are dropped, peers that send a bad piece or bad metadata are banned, DHT replies must match the query we sent, and tracker URLs (which often
  hold a private passkey) never appear in messages.

## Media downloads and Docker

Media links (YouTube and the other sites yt-dlp knows) are downloaded by yt-dlp. A plain `uv tool install ./cli` does
not install yt-dlp, so on that install `anydm` runs yt-dlp, ffmpeg and Deno in a short-lived `docker run --rm`
container from the anydm media image, one per media link and one per link it has to classify. Nothing else is
installed on the host, and Docker is needed only for media. HTTP and torrent downloads never use it.

```bash
make build-media                      # builds anydm-media:<version> (the default image) from cli/dockerfile
anydm get "https://youtu.be/jNQXAC9IVRw" -o ~/Downloads
```

- **Image:** `ANYDM_MEDIA_IMAGE` (or `media_image` in config.toml) names it; the default is the local tag
  `anydm-media:<version>` that `make build-media` builds. If a registry image is missing it is pulled on first use,
  with a notice. A local-only name is never pulled: build it first. The value must look like an image reference
  (letters, digits and `. _ / : @ -`, not starting with `-`); anything else is a usage error (exit 2).
- **Docker command:** `ANYDM_DOCKER` (or `docker`) is the command to run, `docker` by default; anything that accepts
  the same arguments, such as `podman`, may work but is untested.
- **Where these two settings come from:** only the real environment and `config.toml`, never the `.env` file in the
  current folder (which can come from a download); `.env` still works for the other settings.
- **What the container gets:** the URL, the format and the playlist flag. Only the output folder is mounted, at
  `/downloads`; it runs as your uid and gid with no extra capabilities, and none of your environment is passed.
  Paths it reports are checked to lie inside the output folder. The image runs from `/tmp` with Python's
  `PYTHONSAFEPATH` set, so files in your output folder (a `random.py`, a `src/`) are never imported.
- **Root:** `anydm` refuses to start media containers when it runs as root (uid 0): the container would be root with
  a host folder mounted. Run it as a normal user. (Media detection then reports "skipped" and a plain link goes over
  HTTP; `--kind media` exits 1.)
- **Without Docker:** a plain link is downloaded over HTTP, with a notice that media detection was skipped, and
  `--kind media` fails with "media downloads need Docker; see anydm doctor". `anydm doctor` shows whether Docker and
  the image are there (it never pulls or uses the network).
- **In-process yt-dlp:** `uv tool install "./cli[media]"` (or the CLI image below) installs yt-dlp locally. Then media
  runs in-process, as before, with no container, and needs ffmpeg and Deno on the host.
- **Ctrl-C and the TUI:** Ctrl-C, cancelling a row or quitting stops and removes the container within 2 s and keeps
  the partial files. Containers carry labels with the starting process's id and start time; one whose process is gone
  (or whose pid now belongs to another process) is removed on the next media run, before any media check or download.
  This assumes the Docker daemon runs on the same machine as `anydm`. A media check also stops itself after 50 s. To
  clean up by hand: `docker rm -f $(docker ps -aq --filter label=anydm=media)`
- **Pinned and not pinned:** the image pins the Python and Deno versions (tags), and yt-dlp and the other direct
  dependencies (exact versions in `pyproject.toml`). It does not pin the base image digests, ffmpeg (the Debian
  package current when you build), pip and uv (latest at build time) or the dependencies of the direct dependencies
  (resolved at build time; `uv.lock` is not used for the image). Two builds made at different times can differ in
  those.
- **Folders:** on Docker Desktop the output folder must be shared with Docker (`/Users` is by default).
- **Tests:** `make test-docker` builds the image and runs the tests that need a Docker daemon (macOS with Docker
  Desktop). `make test` and `make test-all` never need Docker.

## Docker

The image bundles ffmpeg and Deno, so `anydm doctor` is all green and YouTube works without installing anything on the host.

```bash
make build-prod TAG=anydm-cli:latest
docker run --rm -v ~/Downloads:/downloads anydm-cli:latest get "https://youtu.be/jNQXAC9IVRw"
docker run --rm -it -v ~/Downloads:/downloads anydm-cli:latest tui
docker run --rm -p 6881:6881 -v ~/Downloads:/downloads anydm-cli:latest get "magnet:?xt=..." --seed
```

- It runs as a non-root user (uid 10001). On Linux, pass `--user $(id -u):$(id -g)` so saved files are yours.
- Partial downloads and resume records live in the mounted folder, so a later run resumes.
- For working on the CLI, `make build` and `make docker-run ARGS="get URL"` use the `local` stage with this checkout mounted, saving into `./downloads` (or `ANYDM_HOST_DOWNLOAD_DIR`).
