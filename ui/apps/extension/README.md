# anydm browser extension

Sends a link from a Chrome or Firefox page to the anydm app (OSI-04, #546). Right-click a link, image, video or audio and
choose **Download with anydm**: a small window reads the link through the app's browser bridge, shows the name, size and
kind, and adds it when you press **Add to anydm**. The page's cookies, the browser's User-Agent, the referrer and the page
address go with it, so a download that needs a login or a referrer works.

One Manifest V3 codebase builds both browsers.

```bash
bun install --cwd ui          # once
bun run --cwd ui ext:build    # writes ui/apps/extension/dist/chrome and dist/firefox
bun run --cwd ui ext:test     # unit tests
bun run --cwd ui ext:check    # types
```

## How it works

- The context menu opens `popup.html?url=…` in a small window; the background script holds no state, so a browser that
  stops it between the click and the window loses nothing. The window does the work (`src/flow.ts`): checks the browser
  gave the extension site access, finds anydm, reads the page's cookies, calls `POST /add`, shows the result, and calls
  `POST /confirm` once on Add. The app keeps what it read for 15 seconds; after that the window offers to check the link
  again, which reads the cookies from the browser again, so the extension keeps no cookies, links or history.
- Finding anydm: `GET http://127.0.0.1:<port>/ping` on ports 18400–18409, all at once, within 1.5 seconds. A port is anydm if it
  answers `{"app":"anydm"}`, or `403 forbidden` (anydm is there but does not answer this browser yet). The last working port
  is remembered in the window's `localStorage`, only as a shortcut.
- Only `127.0.0.1` is ever contacted. Nothing is logged. Only the User-Agent goes as a header, never a login, and the cookies
  go as `cookies`, which the app seals in the keychain.
- The toolbar button opens the same window with no link, and shows whether anydm answers this browser.

## The contract with the app

`contract/bridge.json` lists the bodies the extension sends and the answers it handles. The extension's tests read it, and so
does `cross/shared/src/jvmTest/.../os/BridgeContractTest.kt`, which asks the real bridge server with each body. Change the
bridge or the extension without the file and a test fails; change the file and both must pass.

## Which browser the app trusts

The app answers only the extension origins in its settings (`bridgeOrigins`). It starts with this Chrome extension's origin,
`chrome-extension://mbbppgojipgblgakhmhbiakindokfmhn`, which is fixed by the `key` in the Chrome manifest
(`src/manifest.ts`; the private half of the key was thrown away, and a test checks the key still gives that id).

Firefox gives every install its own random origin, so it can't be fixed. Pair it once: click the toolbar button (or try a
download), copy **this browser's address** (`moz-extension://…`), and give it to the app with `Engine.setBridgeOrigins`
(keeping the Chrome one), then press **Try again**. Setting the list to empty means any extension.

**Not built yet:** the app has no settings screen for this. Until one exists, pairing Firefox means calling
`Engine.setBridgeOrigins` from the host, so on a fresh install the Firefox flow stops at the "add this browser" message. The
default also changed with this extension: an install that never set the list used to answer any extension, and now answers
only the Chrome one above.

Firefox also asks the user to grant site access to the extension (Manifest V3 host permissions are not given at install):
the window shows **Allow access** when it is missing.

## Known limits

- Any program on a port in 18400–18409 that answers `/ping` like anydm is taken for anydm, and would be sent the link, the
  page address and the cookies. The app takes the first free port, so a program that got there first wins. The bridge
  protocol has no secret to tell the two apart; closing this needs a change on both sides (a nonce or a signature).
- The link and the page address are in the address of the extension's window, so a browser's "reopen closed window" may
  keep them. Cookies are never in it.

## Manual check (not run by the tests)

Load the build and check each line in both browsers, with anydm running.

1. **Chrome:** `chrome://extensions` → Developer mode → _Load unpacked_ → `dist/chrome`. The extension id is
   `mbbppgojipgblgakhmhbiakindokfmhn`.
2. **Firefox (128 or newer):** `about:debugging#/runtime/this-firefox` → _Load Temporary Add-on_ → `dist/firefox/manifest.json`;
   then _Manage extension → Permissions_ and allow access to all sites.
3. Click the toolbar button: _anydm is running (port 184xx)_. On Firefox before pairing: _does not answer this browser yet_
   with the address to copy; after adding it in anydm's settings, _Check again_ shows running.
4. Right-click a link to a file: the menu has **Download with anydm**; the window shows the file name and size; **Add to
   anydm** adds it, and it appears in the app's list.
5. On a site that needs a login, add a file only a logged-in browser can fetch: it downloads (the cookies went along).
6. Add the same link again: _Already in anydm_, and no Add button.
7. Wait 15 seconds before pressing Add: _The link timed out_; **Check again** reads it again.
8. Quit anydm and try: _anydm is not running_; start it and press **Try again**.
9. Right-click an image or a video: it offers the media file's address.
10. Open a private window (Chrome: allow the extension in incognito) and download a file from a site you are logged in to there
    only: it uses that window's cookies.
