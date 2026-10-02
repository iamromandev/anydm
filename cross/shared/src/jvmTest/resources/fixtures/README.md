Recorded from a throwaway anydm API (8046, with its own rqbit) on 2026-10-02, by
`p2_record.py` in the download-model renovation (plan Task 22). The extract, error and bulk
answers come from endpoints that did not change, and are the 2026-09-28 recordings.

- `task_direct.json` — `GET /download/{id}`: a finished direct download, its one file at index 0.
- `task_torrent.json` — `GET /download/{id}`: Big Buck Bunny downloading, its video file only
  selected, with its `torrent` detail and `live` numbers.
- `task_group.json` — `GET /collection/{id}`: a paused two-video playlist, with `counts` and `folder`.
- `page.json` — `GET /download?page=1&page_size=50`: downloads and a collection, tagged by `type`.
- `summary.json`, `bulk.json` — the sidebar counts, a sweep.
- `extract_media.json`, `extract_playlist.json` — what `POST /extract` says a link is.
- `error_unsupported.json` — `unsupported_url`, which makes a link a direct download.
- `error_extract.json` — a site that couldn't be reached (502, `external_api_error`).
- `events.txt` — fifteen seconds of `/download/events`: the `downloads` snapshot, `progress`
  frames, a direct download's `download` frames, and `collection` frames as the playlist was
  resumed and paused.

Re-record when the API's JSON changes; `FixtureTest` and the recorded-envelope and
recorded-events tests are what notice. Never hand-edit a recorded file.
