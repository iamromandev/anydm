Recorded from a throwaway anydm API (8046, over Postgres on 5446) on 2026-09-28, by the
recording script in the cross part 1 plan, except `task_torrent.json`: that stack ran
without rqbit, so it is written by hand from `TaskSchema`'s fields.

- `task_direct.json` — a finished direct download.
- `task_group.json` — a paused playlist group, with `entry_counts` and `folder`.
- `page.json`, `summary.json`, `bulk.json` — the list, the sidebar counts, a sweep.
- `extract_media.json`, `extract_playlist.json` — what `POST /extract` says a link is.
- `error_unsupported.json` — `unsupported_url`, which makes a link a direct download.
- `error_extract.json` — a site that couldn't be reached (502, `external_api_error`).
- `events.txt` — four seconds of `/download/events`.

Re-record when the API's JSON changes; `FixtureTest` and the recorded-envelope and
recorded-events tests are what notice.
