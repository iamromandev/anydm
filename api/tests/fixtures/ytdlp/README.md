# yt-dlp format fixtures

What yt-dlp reported for one link per site, recorded by the spike in #53 on 2026-09-24. They were recorded from inside the API image, using yt-dlp 2026.08.19, yt-dlp-ejs 0.8.0 and Deno 2.9.7.

Each file keeps only what format selection needs:

- id, protocol and extension
- codecs, dimensions and bitrates
- exact and approximate sizes
- the *names* of the headers yt-dlp would send

There are no URLs and no header values, so nothing here expires and nothing is secret.

Two things these files show that code must respect:

- **An unset codec (`vcodec`/`acodec` missing) means unknown, not absent.** Vimeo's and X's combined MP4s have none recorded. Only the string `"none"` means the stream is absent.
- **`mhtml` formats are storyboard images**, on YouTube and Twitch. They are not media.

The link each file came from is in its `webpage_url`. Re-record by running the #53 probe again when a site changes shape. The tests that read these files should say what they rely on, rather than depending on every detail.
