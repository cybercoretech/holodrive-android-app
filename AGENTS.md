# AGENTS.md — Holodrive for Android (handoff)

## Confirmed product decisions — 2026-10-13

These user decisions supersede the broader file-manager scope and placeholder
identity in the original handoff below:

- App: **Holodrive Photos**, application id **dev.cybercore.holodrive.photos**.
- Native photo gallery for images on a Holodrive server, with albums, viewer,
  downloads, password protection, and the CYBERGRID look.
- Users choose their own server and photo folder. Include access to the full
  Holodrive web GUI in the browser; its login remains separate.
- Support HTTPS servers and HTTP Tailscale servers. The original single-host
  network exception is generalized to `*.ts.net` for distribution, with runtime
  checks limiting requests to the exact configured origin and Tailscale IP
  ranges. Do not permit ordinary HTTP LAN/public endpoints, proxies, redirects,
  or invalid HTTPS certificates.
- Current milestone expands the photo app to accept Android shares and run
  resumable background uploads using the server's existing device-token and
  upload APIs. Broad file management and server administration remain out of
  scope for this milestone. The private repository is
  `https://github.com/cybercore-tech/holodrive`; release signing is not yet
  selected. The local branch is `feat/photo-gallery`.
- In Android Studio, the ordinary `debug` build uses real-server networking and
  screenshot protection. Use `scratchDebug` for isolated synthetic-photo tests.
- The later screenshot about character bios belonged to another task; ignore it.

The remainder is the original server handoff, retained as API/security reference.

> **Working title.** The folder name `Holodrive` and the package id
> `dev.cybercore.holodrive` are placeholders. **Before creating the project,
> confirm the app name and the application id with the user.** Offer 2–3
> options, check for clashes (Play Store, GitHub, `~/Devspace/Android`,
> `~/Devspace/Cybercore`), and let the user choose. Naming is deliberate here.

Written 2026-10-13 by the agent that built the Holodrive server. Read it all
before writing code.

---

## 1. What you're building

A native Android client for **Holodrive**, the web file manager running on
the user's desktop **blackbox**. The phone (Samsung Galaxy S25 Ultra,
tailnet name `sgs25u-dsdx`) reaches it only over **Tailscale**.

The web UI already works in the phone's browser. The app exists to do what a
browser can't do well:

1. **Share target:** "Send to Holodrive" from any app's share sheet, uploading
   to `shared/from-phone` (or a chosen folder). **This is the #1 feature.**
2. **Reliable background uploads:** WorkManager jobs that survive the app
   being closed and retry on network loss, with a notification and progress.
3. **Native browsing:** drives, folders, thumbnails, pictures/video/PDF/text
   preview, download to the phone (MediaStore / SAF), open with other apps.
4. **File operations:** mkdir, rename, move/copy, trash with undo, restore
   from trash.
5. **Later (ask before building):** time machine (snapshots, versions,
   restore), Samba panel (read-only status first), camera auto-upload,
   home-screen widget, Quick Settings tile ("upload last screenshot").

Out of scope unless the user asks: the smb.conf editors, the in-browser code
editor, and any server administration beyond what's listed.

**Suggested MVP, in order (one PR each, never stacked):**
1. Project skeleton, theme, login, drives and folder list with thumbnails.
2. Previews and downloads.
3. Share target, WorkManager upload queue and notifications.
4. File operations and trash.
5. Polish: offline and error states, settings, About.

---

## 2. Where things live

| What | Where |
|---|---|
| This app (to create) | `~/Devspace/Android/Holodrive/` (one Git repo per app; see `~/Devspace/Android/README.md`) |
| Reference Android app (same stack, same conventions) | `~/Devspace/Android/CybercoreContinuity/` |
| Holodrive server source | `~/Devspace/Cybercore/core/holodrive` → github.com/cybercore-tech/holodrive |
| Server API code | `src/web.rs` (routes and guard), `src/fsops.rs` (paths and file ops), `src/snapshots.rs`, `src/samba.rs` |
| Web UI (behaviour reference) | `static/app.js`, `static/ui.css`, `static/index.html` |
| Running server | user service `holodrive.service`, `127.0.0.1:8768`, published by `tailscale serve --http=8768` |
| Server config | `~/.config/holodrive/config.toml` |
| Server logs | `journalctl --user -u holodrive -f` |

**Base URL from the phone:** `http://blackbox.tail9311b5.ts.net:8768`
(plain HTTP *inside* Tailscale's WireGuard tunnel; tailnet HTTPS certificates
are deliberately off). The short name `blackbox` does **not** work: tailscale
serve routes by the full name.

---

## 3. Toolchain on blackbox (verified 2026-10-13)

- JDK 21 (`/usr/lib/jvm/java-21-openjdk`), Gradle, Android Studio
  (`android-studio`). Use the project's `./gradlew`.
- SDK at `~/android` (`ANDROID_HOME`/`ANDROID_SDK_ROOT`, from
  `~/.config/android/env.sh`); platforms up to android-37.x, NDK under
  `~/android/ndk/`.
- adb: `~/android/platform-tools/adb`. Emulator AVD: `Blackbox_API_36`.
  KVM/SVM has been enabled in firmware since 2026-09-27, even though the
  workspace README still says otherwise.
- **Match CybercoreContinuity's stack:** Kotlin, Jetpack Compose and
  Material 3 (Compose BOM), coroutines, `compileSdk 36`, `targetSdk 36`,
  `minSdk 30`, JVM target 17, debug suffix `.debug`.
- **Suggested libraries** (keep the list small, and ask before adding heavy
  ones):
  - OkHttp, plus kotlinx.serialization (or Moshi)
  - Coil, for thumbnails (with the OkHttp client, so cookies and headers apply)
  - Media3 ExoPlayer, for video (HTTP Range is supported by the server)
  - WorkManager, for uploads
  - DataStore, plus Android Keystore for secrets
- Testing on a real phone: ask the user to enable wireless debugging, or USB.
  Never drive the user's desktop GUI (pointer or keyboard) for tests.

---

## 4. Server security model: don't weaken it

The server was hardened deliberately. The app must fit it, not loosen it.

- **Host allowlist:** every request needs
  `Host: blackbox.tail9311b5.ts.net:8768` (OkHttp sends it from the URL).
  Anything else gets **421**.
- **Login:** `POST /api/login` with `{"password": "…"}` returns
  `{"csrf": "<48 hex>"}` and sets the cookie
  `holodrive_session=<id>; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000`.
  - Keep the cookie in an OkHttp `CookieJar`.
  - Keep the CSRF token in memory, or encrypted on disk.
- **Every state-changing request** (anything except GET/HEAD) must send
  `X-Holodrive-Csrf: <csrf>`. Either send no `Origin` header, or send exactly
  `http://blackbox.tail9311b5.ts.net:8768`.
  - OkHttp sends no Origin by default: keep it that way.
  - Missing or wrong CSRF gets **403**.
- **Every `/api/*` call** except `session`, `login` and the `cybergrid` theme
  endpoints needs the session; without it you get **401**. On a 401 in the
  middle of a session, re-authenticate.
- **Login brake:** 5 wrong passwords in 10 minutes means **429** for everyone,
  because all traffic arrives from the proxy. Never retry logins in a loop.
- **Sessions are in memory:** restarting the server signs every client out.
  The app must handle re-login gracefully.
  - Store the password only with the user's consent, encrypted with an Android
    Keystore key, behind biometric unlock.
  - Better: see the token proposal below.
- **Paths are virtual:** `<root id>/<relative path>`, for example
  `shared/from-phone/IMG_1.jpg`. The server refuses `..`, names starting with
  `.` (hidden files are never listed or reachable), symlinks that leave the
  drive, and any write to read-only drives (`writable: false`, e.g. `isos`).
  Mirror these rules in the UI; don't fight them.
- **Raw files** are served with `Content-Security-Policy: sandbox`. HTML and
  SVG come back as attachments. That's fine for a native app.

### Existing server support (confirmed 2026-10-14)

The server now provides device tokens and resumable uploads. The Android app
may create a dedicated writable device token after password sign-in, or accept
one created from the server Devices page. It must keep the secret encrypted
with Android Keystore for workers to resume after process death. Never put a
token in WorkManager input data or logs.

- `POST /api/tokens` (logged in, CSRF) creates a named device token; the secret
  is returned once. `POST /api/tokens/revoke` revokes by id from a browser
  session. The server stores only a SHA-256 hash.
- Requests carry `Authorization: Bearer hd_…`. Bearer access skips CSRF but
  keeps Host validation and cannot manage tokens, share links or Samba.
- Resumable uploads use `/api/upload/start`, `/api/upload/chunk`,
  `/api/upload/status`, `/api/upload/finish`, and `/api/upload/cancel`.
  Chunks are raw bytes and can resume from the server-reported offset.

---

## 5. API reference

All responses are JSON unless noted. Errors are
`{"error": "message"}` with a meaningful status:
- 400 bad input
- 401 not signed in
- 403 forbidden (read-only drive, escaping link, CSRF)
- 404 not found
- 409 conflict (name exists, file changed)
- 413 too big
- 415 not UTF-8
- 422 helper error
- 428 needs confirmation
- 429 brake
- 503 unavailable

`enc()` below means URL-encoding of the whole virtual path.

### Session
- `GET /api/session` returns
  `{authed, csrf|null, configured, host, user, version}`. It works without a
  login. If `authed` is true, use the returned `csrf`.
- `POST /api/login` takes `{password}` and returns `{csrf}` (plus the cookie).
- `POST /api/logout`

### Browse
- `GET /api/roots` returns
  `[{id, name, icon, writable, online, total, free}]`.
  `icon` is an emoji; sizes are bytes.
- `GET /api/list?path=<enc>` returns
  `{path, root, writable, mtime, entries:[Entry]}`.
  - `Entry` is
    `{name, kind:"dir"|"file"|"other", size, mtime(unix s), ext, mime, link, escapes, mode}`.
  - Entries come back directories first, then by case-insensitive name.
- `GET /api/stat?path=` returns `Entry` plus `{path, abs, writable}`. `abs` is
  the real path on blackbox.
- `GET /api/exists?path=` returns `{exists, dir}`.
- `GET /api/search?path=<root or folder>&q=` returns
  `{results:[Entry + path], truncated}`.
  - It matches names only.
  - It's capped at 300 results, 400k entries scanned or 6 s.

### File bytes
- `GET /api/raw?path=[&dl=1]` returns the bytes. It supports **Range**, for
  video seeking and resumable downloads.
  - With `dl=1` it forces `Content-Disposition: attachment`.
  - Text types come back as `text/plain`.
- `GET /api/thumb?path=` returns a JPEG, at most 360 px.
  - It covers images, plus videos when ffmpeg is installed.
  - 404 means no thumbnail: fall back to an icon.
  - Thumbnails are cached for a day.
- `GET /api/zip?p=<enc>&p=<enc>…` returns a zip stream for folders or
  selections. It's built server-side first, so expect a delay before bytes
  arrive.
- `GET /api/text?path=` returns `{path, text, mtime, writable}` (at most
  4 MiB, UTF-8 only).
- `POST /api/text` takes `{path, text, mtime}`. If `mtime` is non-null and the
  file changed since then, you get **409**. Returns `{mtime}`.

### Upload
- `POST /api/upload/start {dir, name, size, overwrite?}` returns
  `{id, offset, size, chunk}`.
- `PUT /api/upload/chunk?id=<id>&offset=<bytes>` takes raw bytes and returns
  `{offset, size}`. On interruption, query status and continue at that offset.
- `GET /api/upload/status?id=<id>` returns `{offset, size}`.
- `POST /api/upload/finish {id}` finalizes as `{path, size}`;
  `POST /api/upload/cancel {id}` abandons it.
- Uploads are staged on the same drive, then atomically moved into place.
  Without overwrite, a name collision gets a unique name.
- `POST /api/upload?dir=&name=` remains available for smaller one-shot uploads.

### Device tokens
- `POST /api/tokens {name, read_only?}` requires a password session and CSRF;
  returns public token metadata plus the secret once.
- Authenticated file requests use `Authorization: Bearer hd_…`. Bearer requests
  skip CSRF but cannot manage tokens, share links or Samba.
- Never place a token in WorkManager input data, logs, or crash messages.

### Changes
All need CSRF.
- `POST /api/mkdir {dir, name}` returns `{path}`.
- `POST /api/newfile {dir, name}` returns `{path}`.
- `POST /api/rename {path, name}` returns `{path}`. 409 if the name exists.
- `POST /api/transfer {paths:[…], dest, copy:bool}` returns `{paths}`.
  Clashes get `(2)`.
- `POST /api/trash {paths:[…]}` returns `{trashed:[{root, id, name}]}`. Keep
  these for **Undo**.
- `GET /api/trash` returns
  `{items:[{root, root_name, root_icon, id, name, from, deleted, kind, size}], days}`.
- `POST /api/trash/restore {root, id}` returns `{path}`.
- `POST /api/trash/purge {root, id}`

### Time machine (snapper; later phase)
- `GET /api/snap/timeline?path=` returns
  `{config, drive, snapshots:[{number, date, type, description, cleanup}]}`,
  newest first. 404 means the drive has no snapshots.
- `GET /api/snap/list?path=&snap=N` returns a list result plus
  `{snapshot, live_exists}`. Entries also have `gone` and `changed`.
- `GET /api/snap/versions?path=` returns
  `{path, current:{size, mtime}|null, versions:[{snapshot, date, size, mtime}]}`.
- `GET /api/snap/raw|thumb|text?path=&snap=N`: same as the live versions.
- `POST /api/snap/restore {path, snap, replace:bool, label}`
  - `replace` puts the current item in the trash first.
  - Returns `{path, trashed}`.

### Samba (status only for the app, at first)
- `GET /api/samba` returns
  `{conf_readable, helper, service, smbstatus, status_error, shares:[{name, path, comment, read_only, browseable, available, extra, drive, exists, connections}], host}`.

### Theme
No login needed.
- `GET /api/cybergrid/themes` returns
  `{active, appearance, themes:[{id, name, family, description, builtin, palette, variants, design}]}`.
  - `palette` has `bg`, `white`, `acid_green`, `hot_pink`, `purple`, `cyan`,
    `orange`, `red`, `panel`, `line`, `muted`.
  - **The hex values have no leading `#`.**
- `GET /api/cybergrid/events` is SSE; a `theme-change` event means the desktop
  switched themes.

---

## 6. Look and feel: Cybercore CYBERGRID

The app should feel like the web Holodrive:
- Dark first, with neon glows and glassy panels.
- Uppercase chrome with letter-spacing.
- The holographic cube logo (`static/holocube.svg`, `static/favicon.svg` in
  the server repo).
- Emoji tags on known folders (`from-phone` 📱, `handoffs` 🤝, `src` 🧬…; see
  `FOLDER_EMOJI` in `app.js`) and file-type emoji (`ICON`).
- Glowing folder tiles tinted per name (`folderColor()`).

**Colours:** build a Material 3 `ColorScheme` from a CYBERGRID palette.
- Default to the dark fallback below.
- Let the user pick any server theme. Remember the choice per device; until
  one is picked, follow `active`.

| role | hex |
|---|---|
| bg | `#07070D` |
| panel | `#0F0F1A` |
| line | `#262640` |
| fg / white | `#E6E6F0` |
| muted | `#8888A8` |
| cyan (primary) | `#00F0FF` |
| hot pink (secondary) | `#FF2BD6` |
| purple (tertiary) | `#9B5CFF` |
| acid green (success) | `#B6FF00` |
| orange (warning) | `#FF9F1C` |
| red (error) | `#FF3860` |

**Font:** JetBrains Mono Nerd (regular, bold and italic woff2 in the server's
`static/fonts/`; get TTFs for Android). Respect "remove animations" and
dynamic font size. Small touches go a long way: a cyan→pink gradient on
primary buttons, a faint scanline overlay, the synthwave grid on the login
screen.

---

## 7. Rules for working here

- **Git and GitHub:** all Cybercore work is on the **cybercore-tech** GitHub
  account.
  - Before every commit, run `git config user.email`. It must be
    `332800418+cybercore-tech@users.noreply.github.com` once the repo's remote
    points at cybercore-tech; identities follow the remote via `includeIf`.
    Never commit as a personal Gmail address. If the check fails, abort the
    whole script, not just the next command.
- **PRs:** always branch → push → PR → **the user merges**.
  - **Never stack PRs.** Open the next one only after the previous one is
    merged; check with `gh pr view N`.
  - Watch CI (`gh pr checks N`) before calling a PR done.
- **Creating the GitHub repo:** ask first. The user creates repos (empty, no
  README) and decides public or private.
- **Names** (app, package id, repo): propose options, check for clashes, and
  let the user choose.
- **Secrets:** signing keys and keystores live only in a restricted
  `~/Vaults` location, never in Git, the Wiki or Cyberdesk. Never log the
  password, cookie, CSRF token or device token.
- **Never** run `pkill -f` with a pattern that matches your own shell.
- **Testing the server:** use a **scratch instance**
  (`holodrive --config <scratch config>` on another port, with
  `XDG_CACHE_HOME` pointed at scratch). Don't spray wrong passwords at the
  live one: it brakes logins for everyone.
- **Fix logs:** write one for any change to blackbox's system setup, in
  `~/Documents/Blackbox/system/fixes/YYYY-MM-DD_HHMM_<slug>.md`.
- **Stay in your lane:** don't touch smb.conf, the Samba helper, polkit,
  Tailscale or firewall settings from this project.

---

## 8. Gotchas we already hit

- Use the full tailnet name; `blackbox:8768` won't route.
- Plain HTTP requires an Android Network Security Config that allows
  cleartext **only** for `blackbox.tail9311b5.ts.net`. Don't set
  `usesCleartextTraffic="true"` app-wide.
- Upload chunks must stream from disk through an OkHttp `RequestBody`; never
  load whole shared files into memory because phone videos can be gigabytes.
- `/api/zip` builds the archive first. Show a "packing…" state, and use long
  read timeouts.
- Thumbnails of videos need ffmpeg on the server (present on blackbox); expect
  a 404 otherwise.
- Paths with spaces and non-ASCII characters are normal: always URL-encode
  query values.
- Theme palette hex comes without `#`.
- Server restart means a 401 everywhere: re-login (or use device tokens).

---

## 9. Definition of done, per PR

- `./gradlew assembleDebug test lint` is green, and CI is green if the repo
  has CI (add a GitHub Actions workflow like the server's).
- The app was tested on the emulator `Blackbox_API_36` against a scratch
  server, and on the real phone over Tailscale with the user's OK.
- No secrets in Git, and no cleartext outside the one host.
- README updated; screenshots for UI PRs.
