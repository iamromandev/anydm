# anydm browser extension

Sends a link from a Chrome or Firefox page to the anydm app (OSI-04, #546). Right-click a link, image, video or audio and
choose **Download with anydm**: a small window reads the link through the app's browser bridge, shows the name, size and
kind, and adds it when you press **Add to anydm**. The browser's User-Agent, the referrer and the page address go with it,
and, once you have **paired** the browser with the app (below), the page's cookies too, so a download that needs a login
works.

It can also take over the browser's own downloads, so a click on a download link goes straight to anydm. That needs a paired
browser, and is **off until you turn on _Capture browser downloads_** in the toolbar window; the browser keeps any download
anydm does not take.

One Manifest V3 codebase builds both browsers.

```bash
bun install --cwd ui          # once
bun run --cwd ui ext:build    # writes ui/apps/extension/dist/chrome and dist/firefox
bun run --cwd ui ext:test     # unit tests
bun run --cwd ui ext:check    # types
```

## Pairing with the app

Any program can listen on a port in 18400–18409 and answer `GET /ping` like anydm, so answering is no proof. Pairing gives the
browser and the app a shared secret, and the browser sends cookies, and takes over downloads, only to a program that proves it
holds it.

1. In anydm open **Browsers** and choose **Pair a browser**. It shows a code of 16 letters and digits
   (`XXXX-XXXX-XXXX-XXXX`), good for two minutes and one use; five wrong tries burn it, and there is a limit of ten tries a
   minute.
2. Click the extension's toolbar button, type the code into **Pair with anydm** (spaces, hyphens and lower case are fine; `O` is
   read as `0` and `I`, `L` as `1`) and press **Pair**. The status then reads **Paired with anydm.**
3. **Forget this pairing** takes the secret out of this browser. It does not end the pairing in anydm: revoke the browser in
   anydm's Browsers list too. If anydm revokes a browser, the toolbar window says anydm no longer knows it, and nothing is
   sent until you pair again or forget the pairing (the extension does not drop its secret by itself on that answer, because
   any program that answers like anydm could say it to make the extension forget).

What happens on the wire (the exact formulas are pinned by `contract/pairing-vectors.json`, which the app's tests read too):

- The code never leaves the window. The extension sends a fresh random `nonceE` and `proofE`, an HMAC-SHA-256 of it under the
  code, bound to the TCP port it dialed. The app answers with its own proof of the code; the extension checks it (with
  `crypto.subtle.verify`, so the comparison is constant time) and only then keeps `{id, secret}`. A program that does not know
  the code can't make that proof, so it is skipped, the next port that answers is tried, and, if none is the app, the window
  says **wrong code, or not the anydm app**.
- Each use starts with a hello: the extension proves it knows the secret, and the app proves the same back. Only a port whose
  hello verified is sent anything private. Every port that answers is asked at once, so a program that answers `/ping` and
  then says nothing costs one deadline, not one per port; a program that fails its proof is skipped, and the verified port
  that worked last (else the lowest) is the one used. With none verified, nothing is sent and the window says so.
- **Cookies are sent only in a session verified a moment ago.** The app can't tell the extension that it has quit, and a
  program that takes its port answers the next signed request as it pleases, so a session older than 5 seconds (by the longer
  of the monotonic and the wall clock, and a wall clock set back counts as old) is proved again with a fresh hello, in the same
  turn of the queue, before an `/add` that carries cookies goes. A request without cookies keeps its session.
- **Deadlines.** `/pair` and `/hello` get 4 seconds (the app answers them from memory and a small file); only `/add` and
  `/confirm`, where the app reads a link, wait 35 seconds. Requests go one at a time, so a program that answered `/ping` and then
  stalled could otherwise hold every other request for as long as it was waited for. Every request is made with
  `redirect: "error"`: nothing on a port may send a body, a signature or cookies on to another address.
- A paired browser signs every `/add` and `/confirm` (headers `X-Anydm-Id`, `X-Anydm-Counter`, `X-Anydm-Mac`; the MAC covers
  the nonce of the session, the counter, the path, the SHA-256 of the exact body bytes and the port). The counter goes up with
  every request, and requests are sent one at a time. A session lasts 60 seconds on the app's side; when the app answers
  `session_expired`, `unauthorized` or `replay` the extension says hello again, **once** for that request, and gives up if
  that does not help. `nonceE` is 16 fresh bytes from `crypto.getRandomValues` for every hello and every pairing.
- Pairing that the app accepted but this browser could not keep (`storage.local` refused the write) says so: the code is
  spent, so choose **Pair a browser** in anydm again for a new one; anydm holds a pairing nobody has the secret of, which can
  be revoked in its Browsers list.
- Transient answers are not taken for revocation. `503 store_unavailable` (anydm can't read its list of paired browsers just
  now) keeps the secret and asks the person to try again; a `/pair` that answers `500 not_saved` is tried once more, and its
  code still works.
- Only one part of the extension says hello: the background worker owns the session (the app has one session per paired
  browser, and a new hello ends the last). The windows ask the worker through `runtime.sendMessage` (`src/rpc.ts`).
- The secret lives in `storage.local` only, and is never logged or shown. The id and secret are not in any address, message
  to a page or popup text.

When no port pairs, the window says why with the most useful of the refusals it got (no code waiting, wrong code, ...). None of
those refusals carries a proof, so a program on a lower port can make **wrong code** the one shown and hide the real app's
**no code waiting**; it can't pair the browser, and it never has the code (only a proof of it, bound to its own port). The
message is a hint, not an authentication.

A browser that is not paired keeps working for the menu entry as before: the link, the referrer and the User-Agent go to the
program that answers, never cookies, and capture does nothing. Firefox's origin step (below) comes first: pairing is by code,
but the app answers only extension origins it knows.

## How it works

- The context menu opens `popup.html?url=…` in a small window; the background script holds no state of its own about the
  link, so a browser that stops it between the click and the window loses nothing. The window does the work (`src/flow.ts`):
  checks the browser gave the extension site access, asks the background worker to find anydm (and, if paired, to verify
  it), reads the page's cookies only if it verified, calls `POST /add`, shows the result, and calls `POST /confirm` once on
  Add. The app keeps what it read for 15 seconds; after that the window offers to check the link again, which reads the
  cookies from the browser again, so the extension keeps no cookies, links or history.
- Finding anydm: `GET http://127.0.0.1:<port>/ping` on ports 18400–18409, all at once, within 1.5 seconds. A port is anydm if it
  answers `{"app":"anydm"}`, or `403 forbidden` (anydm is there but does not answer this browser yet). The last working port
  is remembered in the window's `localStorage`, only as a shortcut.
- Only `127.0.0.1` is ever contacted. Nothing is logged. Only the User-Agent goes as a header, never a login, and the cookies
  (paired and verified only) go as `cookies`, which the app seals in the keychain. A body that would pass the app's 64 KB cap
  goes without the cookies, and says so.
- The toolbar button opens the same window with no link, and shows whether anydm answers this browser and whether it is
  paired, and holds the settings: **Capture browser downloads** (off by default), **Skip files smaller than N MiB** (1 MiB is
  1,048,576 bytes) and a list of **excluded sites** (and their subdomains). Both rules apply only to captured downloads, so
  they do nothing while capture is off, and the window says so. **Download with anydm** in the menu is a deliberate send and
  is never filtered.

## Taking over browser downloads

`downloads.onCreated` (`src/background.ts`) hands each new download to `Capturer` (`src/capture.ts`), which never waits for
the browser: the browser's download goes on while anydm is asked. In order:

1. **Which downloads.** Nothing is done unless _Capture browser downloads_ is on **and this browser is paired**. Left to the
   browser, with no call to anydm: addresses that are not `http:` or `https:` (`blob:`, `data:`, `file:`, `filesystem:`,
   `ftp:`...), downloads another extension started, downloads of a private window, downloads that are no longer running, **a
   page saved as HTML (`text/html`) and any download whose file already has a name** (Ctrl+S, Save link as: the person chose
   it, and anydm can't fetch the page as the browser has it), excluded sites (the address and the address after
   redirects), and a file the browser already knows to be smaller than the minimum. **On Firefox, when the browser has more
   than one cookie store that is not a private window's (containers), nothing is taken:** a download has no tab, so it can't
   say which container's cookies it needs. A size of -1, 0 or none is unknown, and an unknown size is not "small". The same
   download announced twice is handled once (by its id, kept in memory). At most three downloads are read at a time, thirty
   more may wait, and the rest are left to the browser.
2. **Ask anydm.** The same flow as the menu, to a port whose hello verified: the link with the cookies the browser holds for
   that address (without the ones marked `SameSite=Strict`, unless the page the download came from is on the same host as
   the download, because the browser would not send those from another site), the User-Agent and the referrer (`POST /add`).
   anydm's own look at the link gives the file name (including `Content-Disposition`) and the size, so they are not taken from
   the browser's item, which has no file name yet when it begins; a size under the minimum found this way also leaves the
   download to the browser.
3. **Is it the same file?** The browser's copy is kept when the cookies were too many to send (anydm then read the link as a
   stranger would and may have been shown another file), or when the size anydm found differs from the size the browser
   knows (looked up again just before; a size either side does not know is not a difference).
4. **Confirm.** Only if the browser's download is still running, and capture is still on (turning it off while a link is read
   stops taking that download), does the extension confirm the link (`POST /confirm`).
5. **Cancel.** Only after anydm has answered that it added the link, after the extension proved once more that the program on
   that port holds the secret (the answer to `/confirm` carries no signature), and only if the browser's download is still
   running, the extension calls `downloads.cancel` and then `downloads.erase` on it. Erase is skipped if the download completed
   in the meantime. A cancel that fails is not followed by an erase.

If anydm is not running, does not answer this browser, is not the paired app, refuses or fails, takes too long, already has the
link, or the browser has not given the extension site access, **nothing is cancelled or erased** and nothing is shown: the
browser downloads the file as if the extension were not there. A download the browser finishes first (a small file) is left as
it is. If the extension is stopped between anydm's answer and the cancel, the file is downloaded twice, never zero times.

The answer **200 from `/confirm` means the link is queued, not that it has started**: if anydm then fails to start it, the
browser's copy is already gone, and the download is in anydm's list as failed or paused for the person to retry.

## Permissions and privacy

| Permission                 | Why                                                                                                                                                                                                                                                                                                                                                                                                |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `contextMenus`             | The **Download with anydm** entry.                                                                                                                                                                                                                                                                                                                                                                 |
| `cookies`, host access     | To read the cookies the browser holds for a link, and to reach `127.0.0.1`. Firefox asks for host access once.                                                                                                                                                                                                                                                                                     |
| `activeTab`                | The toolbar window.                                                                                                                                                                                                                                                                                                                                                                                |
| `storage`                  | The settings and the pairing (`storage.local`): the capture switch, the minimum size, the excluded sites, and the pairing's id and secret.                                                                                                                                                                                                                                                         |
| `downloads` (**optional**) | Asked for when you turn _Capture browser downloads_ on, together with access to all sites, in one question, and given back when you turn it off (site access stays: the menu entry needs it). To see that a download began and, once anydm has it, to cancel and erase the browser's copy. Chrome shows this as "Manage your downloads". If you say no, capture stays off, and the window says so. |

Privacy: the extension talks only to programs on `127.0.0.1`, and logs and keeps nothing (no history, no cookies; the only
memory is the numbers of the downloads it has handled, until the browser stops the extension, and the pairing's id and
secret in `storage.local`). With capture off, a download is looked at only to see that it is off (and without the
`downloads` permission, which is asked for when capture is turned on and given back when it is turned off, the extension sees
no download at all). With
capture on, the address of a download that is not left to the browser, its referrer, the browser's User-Agent and the cookies
the browser holds for that address go to the paired local app only: capture does nothing until the browser is paired, and
sends nothing to a port whose proof failed. The menu entry sends the link, the referrer and the User-Agent to the program on
the port even when the browser is not paired (never cookies), as it always did. The page's contents, the file and the
browsing history are never read.

Firefox (AMO): before this is published, the manifest needs `browser_specific_settings.gecko.data_collection_permissions` for
the add-on's data practices. Everything here goes to a program on the same computer, which is not transmitted off the device;
check that it still reads `required: ["none"]`, or the right entries, when it is filled in. (The extension is not published
yet, so the key is not in the manifest.)

## The contract with the app

`contract/bridge.json` lists the bodies the extension sends and the answers it handles. The extension's tests read it, and so
does `cross/shared/src/jvmTest/.../os/BridgeContractTest.kt`, which asks the real bridge server with each body.
`contract/pairing-vectors.json` holds the proofs, MACs and code readings, made by Python's `hmac`, not by either side; the
extension's `pairing.test.ts` and the app's `PairingVectorsTest.kt` both read it. `contract/pairing-http.json` holds the HTTP of
pairing: for `/pair`, `/hello` and a signed `/add` and `/confirm`, each case's state, request (path, headers, body fields), and
the status, error code and message of the answer, with every error string. The extension's `contract.test.ts` puts each case to
`FakeApp` (which builds its answers from the file, and is what the other tests use as the app), and
`cross/shared/.../os/PairingHttpContractTest.kt` puts the same cases to the real `BridgeServer`; `contract.test.ts` also checks
that what the extension sends has the fields and headers the file lists. Change the bridge or the extension without
the file and a test fails; change the file and both must pass.

## Which browser the app trusts

The app answers only the extension origins in its `bridgeOrigins` list. It starts with this Chrome extension's origin,
`chrome-extension://mbbppgojipgblgakhmhbiakindokfmhn`, which is fixed by the `key` in the Chrome manifest
(`src/manifest.ts`; the private half of the key was thrown away, and a test checks the key still gives that id).

Firefox gives every install its own random origin, so it can't be fixed, and it has to be added to that list. The extension
shows the address to copy (`moz-extension://…`) when you click the toolbar button or try a download. **But the new desktop
app has no settings screen, and nothing in its window can edit `bridgeOrigins`** (the engine has `Engine.setBridgeOrigins`,
and `DesktopEngine` reads the list, but nothing calls the setter from the UI). So Firefox cannot pair through the desktop
app today: on a fresh install its flow stops at the "does not answer this browser yet" message. An app-side way to allow a
Firefox extension origin is a follow-up. Setting the list to empty would mean any extension. Pairing by code comes after
this step, for both browsers. The default also changed with this extension: an install that never set the list used to answer
any extension, and now answers only the Chrome one above.

Firefox also asks the user to grant site access to the extension (Manifest V3 host permissions are not given at install):
the window shows **Allow access** when it is missing.

## Known limits

- **Pairing closes the cookie and capture hole, not every hole.** Without pairing, any program on a port in 18400–18409 that
  answers `/ping` like anydm is taken for anydm by the menu entry, and is sent the link, the referrer and the User-Agent. It
  never gets cookies, and capture does nothing, until the browser is paired and the program proves it holds the secret.
- **The app still accepts an unsigned `/add` without cookies from any local process that sends a valid extension `Origin`.**
  That is the old menu flow, kept so an unpaired extension works. A "paired browsers only" setting is a possible later change.
- **Someone who can capture loopback traffic** (that takes administrator rights) sees the nonces and proofs of a pairing. The
  code has 80 bits and every proof is bound to the port, so they can't work the code out offline (2^80 HMACs), and a relay on
  another port can't forward a pairing or a session to the real app. They can still burn a waiting code with wrong tries,
  which is a local denial of service any local program can cause, and the code's two-minute life does not protect a capture.
- The app's answers to `/add` and `/confirm` are not signed. A program that took over the port in the 60 seconds after the app
  quit could answer "added"; capture proves the app once more right before it cancels, which narrows this to the instant
  between that proof and the cancel. Cookies are narrower still: they go only in a session verified within the last 5 seconds,
  so a program that takes the port after the app quit never sees them (it may see a link without cookies, as a program on the
  port always could for the menu entry).
- **200 means queued, not started.** After `/confirm` answers 200 the browser's copy is cancelled; if the app then fails to
  start the download, it is in the app's list as failed or paused for the person to retry.
- **Quarantine, and the Mark-of-the-Web, are not set on files anydm saves.** The browser sets them on its own downloads (macOS
  quarantine flag, the Windows zone identifier), so a file anydm took over from the browser lacks the warning the browser
  would have given. That has to be done by the app (a follow-up there), not by the extension.
- Capturing needs the extension to have site access (Chrome gives it at install; Firefox asks, see above) and the optional
  `downloads` permission (asked when capture is turned on). Without them the browser keeps every download, and nothing says
  so, apart from the note in the toolbar window.
- A downloaded file has no tab, so the extension cannot tell which Firefox container it came from. It reads the cookies of
  the default store, and, when Firefox has more than one non-private store, leaves every download to the browser (above).
  Private windows are left to the browser for that reason too. If a login the default store does not hold is what makes the
  link work, anydm's read of the link fails or sees another page; the browser's download is then kept, but turn capture off if
  it hurts.
- A download the browser has flagged as dangerous is not told apart: anydm reads the link without the browser's warning.
- A link already in anydm is not taken again: the browser downloads it as usual.
- The browser may show its download bar or "Save as" for a moment before the extension cancels its copy.
- The link and the page address are in the address of the extension's window, so a browser's "reopen closed window" may
  keep them. Cookies are never in it.

## Manual check (not run by the tests)

Load the build and check each line in both browsers, with anydm running. The tests use a model of the app and do not run a
browser: everything about real `runtime.sendMessage`, `permissions.request`, the browser's download timing and the app's real
sockets is for these checks.

1. **Chrome:** `chrome://extensions` → Developer mode → _Load unpacked_ → `dist/chrome`. The extension id is
   `mbbppgojipgblgakhmhbiakindokfmhn`.
2. **Firefox (128 or newer):** `about:debugging#/runtime/this-firefox` → _Load Temporary Add-on_ → `dist/firefox/manifest.json`;
   then _Manage extension → Permissions_ and allow access to all sites.
3. Click the toolbar button: _anydm is running (port 184xx)_ and **Not paired.** On Firefox: _does not answer this
   browser yet_ with the address to copy. The desktop app has no settings screen and nothing in its window can add that
   address, so on Firefox this stays as it is today (it is a known limit, not a failure of this check); Chrome shows running.
4. **Pair.** In anydm open Browsers → Pair a browser; type the code into **Pair with anydm** (try it with spaces and in lower
   case): the status reads _Paired with anydm._ and the browser appears in anydm's list. A wrong code says _Wrong code, or not
   the anydm app_; a code typed with nothing waiting in anydm says it is not waiting for one.
5. **Forget, revoke.** Press **Forget this pairing**: _Not paired._ again. Pair again, then revoke the browser in anydm: the
   toolbar window says anydm no longer knows it, a menu send fails with that text, and _Forget_ still works.
6. **A program on a lower port.** Start a small script that answers `{"app":"anydm"}` to `/ping` and anything to the rest on
   18400 before anydm, so anydm takes 18401. Pair and use the menu entry: it still works (the program on 18400 is skipped),
   and the script is sent only the pings and a `/hello`, no cookies and no link.
7. Right-click a link to a file: the menu has **Download with anydm**; the window shows the file name and size; **Add to
   anydm** adds it, and it appears in the app's list. Do it paired and not paired.
8. On a site that needs a login, add a file only a logged-in browser can fetch, **paired**: it downloads (the cookies went
   along). Not paired: it fails or is another page, and the window says the cookies were not sent.
9. Add the same link again: _Already in anydm_, and no Add button.
10. Wait 15 seconds before pressing Add: _The link timed out_; **Check again** reads it again.
11. Wait over a minute after the window opened, then press Add: it still adds (the extension says hello again by itself).
12. Quit anydm and try: _anydm is not running_; start it and press **Try again**.
13. Right-click an image or a video: it offers the media file's address.
14. Open a private window (Chrome: allow the extension in incognito) and download a file from a site you are logged in to there
    only: it uses that window's cookies.
15. **Capture on.** In the toolbar window turn **Capture browser downloads** on: the browser asks once for downloads and for all
    sites. _Chrome may close the toolbar window when it asks_, so the setting is saved as on before the question is answered
    and put back if the answer is no. Allow it, open the window again, and it says capture is on. Without restarting the
    browser, click a link to a large file: it appears in anydm and the browser's own download is gone (not listed, no partial
    file). Say no to the question (and, in Chrome, open the toolbar window again if it closed): with the window still open it
    says capture stays off; if it was closed by the question, the switch reads on with the note that the browser no longer
    gives the extension the access, and turning it off and on again asks again. Either way a download is left to the browser.
    Turn the setting **off**: capture stops at once, and the extension's permissions page no longer lists _Manage your
    downloads_ (site access stays). Turn it on again: the browser asks again and capture works without a restart (the
    download listener is registered again when the permission comes back). With the setting never turned on, the extension
    holds no `downloads` permission.
16. Turn capture on **before** pairing: the window says it starts once the browser is paired, and downloads stay with the
    browser.
17. Quit anydm and click a download link: the browser downloads it normally. Start anydm, set the minimum to 5 MiB, and click a
    link to a 1 MiB file: the browser keeps it. Add the site to the excluded list: the browser keeps its downloads. With
    capture off the window says these two settings do nothing.
18. Click a link to a very small file with anydm running: the browser's copy completes and stays (anydm usually does not add
    it, and may if the download finished just after anydm accepted it).
19. Check `blob:` (save an image from a page that builds it), `data:` and `file:` downloads are not taken, and that a download
    in a private window is not taken.
20. **Ctrl+S / Save page as** on a page: the browser saves it and nothing is added to anydm. The same for **Save link as…**
    (the file already has a name). _If Firefox gives every download a file name when it announces it, capture takes
    nothing there: this check, and the rule in `decide` that leaves a download with a name, then need another look._
21. **Firefox containers:** open a tab in a container, then click a download link anywhere: the browser keeps the download
    (several stores). Close the container tabs and the stores that remain: capture works again when only the default store is
    in use.
22. A download that needs a login the page's cookies hold, with `SameSite=Strict` cookies: from a link on the same site it is
    taken; from a link on another site it is read without the Strict cookies (and the browser keeps its copy if anydm then
    sees a different file size).
23. Click twenty download links at once: the browser downloads them, anydm takes them in turn (three at a time), and none is
    lost: a download is either in anydm's list or still the browser's.
24. Right-click a link in the menu while the minimum and the excluded list are set: **Download with anydm** still adds it.
