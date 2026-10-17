# 📸 Holodrive Photos

A native Android client for [Holodrive](https://github.com/cybercore-tech/holodrive), with separate Photos and Files navigation, device-token sign-in, and resumable background uploads from Android's share sheet.

**Application ID:** `dev.cybercore.holodrive.photos` (debug: `.debug`). Android 11+, Kotlin / Jetpack Compose / Material 3. This is an early release build and is not published through an app store.

## 📲 Install Holodrive Photos

**[GitHub Releases](https://github.com/cybercore-tech/holodrive-android-app/releases)** · [Install with Obtainium](https://github.com/cybercore-tech/holodrive-android-app#automatic-updates-with-obtainium) · [Android app guide](https://cybercore-tech.github.io/holodrive-android-app/)

The first signed APK release is being prepared. After `v0.1.0` is published, the stable latest-release download URLs below will be active.

```sh
curl -fL -o holodrive-photos.apk https://github.com/cybercore-tech/holodrive-android-app/releases/latest/download/holodrive-photos.apk
curl -fL -o holodrive-photos.apk.sha256 https://github.com/cybercore-tech/holodrive-android-app/releases/latest/download/holodrive-photos.apk.sha256
sha256sum --check holodrive-photos.apk.sha256
```

Android 11 or newer is required. To update, install a newer signed release over the existing app; Android verifies that updates use the same signing key. For automatic update checks, install [Obtainium](https://github.com/ImranR98/Obtainium), choose **Add app**, and enter `https://github.com/cybercore-tech/holodrive-android-app`.

## 🚀 Using the app

1. Open **Configure your server**. Enter an HTTPS Holodrive origin, or a full Tailscale URL such as `http://blackbox.tail9311b5.ts.net:8768`. Include the port your server actually uses. HTTP IP addresses, LAN hostnames, embedded credentials, and path prefixes are rejected.
2. Connect Tailscale if using a tailnet server. Sign in with the Holodrive password, or create a token on the server's **Devices** page and choose **Device token**. You can also enable background uploads from Settings after password sign-in; the app creates a dedicated device token for this phone.
3. **Photos** asks you to choose photo folders the first time; folders added to **Settings → Manage photo folders** appear together in one gallery. Remove folders there at any time. **Files** browses every configured drive; save frequently used server folders from **Settings → Manage file folders** for quick access at the Files root.
4. Open a subfolder as an album. Photos in the current folder are shown newest-modified first. Search filters filenames in that folder; this is not recursive indexing or EXIF capture-date sorting.
5. Use **Photos** for the selected photo library, or **Files** to browse all configured drives and download any file. In Photos, tap an image, swipe between photos, pinch or double-tap to zoom. **Save original** streams the original to a location selected through Android's document picker.
6. **Open Holodrive** opens the full server GUI in your browser at the current folder. Browser authentication is separate; passwords and session tokens are never passed in the URL.
7. Share one or more files from any Android app to **Holodrive Photos**. Choose the destination folder and add them to the upload queue. Uploads run in the background, show progress notifications, resume interrupted chunks, and retry when the network returns.
8. In Settings, pair an Android folder with a Holodrive folder and choose **Phone → server**, **Two-way**, or **Server → phone** for each pair. Set automatic sync to every 15 minutes, hourly, every 6 hours, or daily; choose Wi-Fi-only; or run a pair manually. Background sync requires an encrypted device token and the Android folder permission. Two-way sync favors the newer modified time (phone wins ties); it never propagates deletions. Choose photo and file layouts and grid sizes, sort each view, select the default Photos or Files tab, and save the upload destination. Select a theme or follow the server.

The gallery still displays folders and raster photos; non-photo files, hidden names, and links escaping the shared root are excluded. Uploads accept files shared by other Android apps. Sync copies files and folders but does not propagate deletions; background sync cadence is managed by Android and may be delayed by battery/network policy. Video playback and server administration are not included.

From a photo viewer or a file's details sheet, create a password-protected share link. Choose **Generate with Keysmith** to make a passphrase locally, then share the link and passphrase separately. Link creation requires password sign-in; device tokens cannot manage links.

## 🛡️ Security and privacy

- HTTPS uses the normal Android certificate trust store; no certificate bypasses.
- Plain HTTP is permitted only for `*.ts.net` in Android's network configuration. Each request is additionally confined to the **exact configured scheme, host, and port**. HTTP DNS results must be within Tailscale's IPv4 / IPv6 ranges. System HTTP proxies and redirects are disabled.
- HTTP itself is not encrypted. For tailnet URLs, Tailscale encrypts the phone-to-server transport using WireGuard. Tailscale must be installed, connected, and authorized separately. The app does not configure Tailscale or the server's access policies.
- The configured server must retain its Host allowlist, password protection, CSRF checks, and login throttle. The app uses session cookies for password sign-in and Bearer authentication for device tokens. It never retries password submissions automatically.
- Passwords are never stored unless **Remember on this device** is checked; they use AES-GCM with an Android Keystore key requiring fingerprint confirmation. A device token is stored only when requested, encrypted with a device-bound Android Keystore key so WorkManager can resume uploads without an interactive prompt. The server currently grants writable tokens access to every writable drive; the app explains this before enabling background uploads. Tokens can be revoked from the server's **Devices** page. Sign-out erases local credentials and cancels queued uploads.
- Shared files are copied into private app storage before they are queued, so temporary Android share permissions do not expire during background work. Staged files remain on device until their upload succeeds or the share is dismissed.
- Keysmith passphrases are generated on-device from the pinned EFF Large Wordlist with Android's `SecureRandom`; the app does not call a generator service. The passphrase is sent to Holodrive only to create its password-protected link, where the server hashes it. Link URLs are bearer capabilities; copied links and passphrases are marked sensitive in Android's clipboard metadata and should be sent through separate channels.
- Preferences store the server address, selected virtual folder, and theme. Android cloud backup/device transfer are disabled. Production builds block screenshots and recent-task previews via `FLAG_SECURE`.
- No analytics, ads, telemetry, contacts, camera, or broad storage permissions. Explicitly saved photos are normal files at the destination chosen by the user, outside the app's session protection.

See [PRIVACY.md](docs/PRIVACY.md) and [VALIDATION.md](docs/VALIDATION.md).

## 🧰 Build

Use JDK 21, Android SDK 36 / build tools 36.0.0, and the checked-in Gradle wrapper. `local.properties` should contain your SDK path and is ignored by Git.

Debug signing material stays outside the repository at `~/Vaults/android/holodrive-debug.keystore`. To create a local debug key:

```sh
mkdir -p "$HOME/Vaults/android"
chmod 700 "$HOME/Vaults/android"
keytool -genkeypair -keystore "$HOME/Vaults/android/holodrive-debug.keystore" -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
chmod 600 "$HOME/Vaults/android/holodrive-debug.keystore"
./gradlew assembleDebug test lint assembleRelease
```

Android Studio's ordinary `debug` variant is the safe default. It builds the normal signed debug app at `app/build/outputs/apk/debug/app-debug.apk`. Choose `scratchDebug` only when running the isolated synthetic-photo server. The optimized `serverRelease` variant uses the dedicated release key when the `HOLODRIVE_RELEASE_*` environment variables are set; otherwise it remains unsigned. Release key material and its private environment file stay in `~/Vaults/android/` and must never be committed. Build a signed release with:

```sh
set -a
. "$HOME/Vaults/android/holodrive-photos-release.env"
set +a
./gradlew :app:assembleServerRelease
```

The signed APK is written under `app/build/outputs/apk/server/release/`. Keep the keystore backed up securely; future app updates must use this same key. The pinned stack matches CybercoreContinuity; dependency/target upgrade advisories remain visible in lint.

## 🧪 Isolated emulator testing

Never test wrong passwords or change files on the production instance. `tools/prepare_scratch.py` creates synthetic photos and a separate password-protected config under a private temporary directory (Python Pillow and argon2-cffi required). Its random scratch password is kept in `~/.local/share/holodrive-photos/scratch-password` (mode 0600) and reused across scratch server rebuilds; save it in the browser's password manager once for the local GUI. This credential is only for the loopback test server, not your real Holodrive server. In Android Studio select **View → Tool Windows → Device Manager** and start `Blackbox_API_36`. Open the **Build Variants** tool window, select `scratchDebug`, then press **Run ▶ app**. This is a separate install that points to generated photos.

```sh
python3 tools/prepare_scratch.py
# Replace REPLACE_ME with the temporary directory printed above:
SCRATCH_DIR=/home/raven/.cache/omarchy/tmp/holodrive-photos-REPLACE_ME
XDG_CACHE_HOME="$SCRATCH_DIR/cache" /home/raven/.local/bin/holodrive --config "$SCRATCH_DIR/config.toml"
# In a second terminal, after the emulator has started:
adb -s emulator-5554 reverse tcp:8768 tcp:18768
```

With the scratch server and emulator running, use `python3 tools/emulator_ui.py install` to install/update the scratch APK, sign in with the local fixture credential automatically, and open the synthetic photo gallery. No password lookup or Android Studio interaction is needed for this repeat test launch. To sign into the separate browser GUI, the credential is saved at `~/.local/share/holodrive-photos/scratch-password`.

The scratch variant resolves only `blackbox.tail9311b5.ts.net` to emulator loopback, preserving Holodrive's Host header. It can display screenshots of generated fixtures. Both release variants always protect screenshots and never use the loopback override. Select the ordinary `debug` variant before using a configured real server or sharing a debug APK. Screenshots in `docs/screenshots` contain generated fixtures, never personal photos.

## 🔄 Automatic updates with Obtainium

1. Install Obtainium from its [official GitHub releases](https://github.com/ImranR98/Obtainium/releases/latest).
2. Tap **Add app** and paste `https://github.com/cybercore-tech/holodrive-android-app`.
3. Confirm the detected package is `dev.cybercore.holodrive.photos` and save. Obtainium checks GitHub Releases for updates; Android asks before installing each update.

## 📦 Release preparation

The Android source repository is `https://github.com/cybercore-tech/holodrive-android-app`; the Holodrive server and web GUI are in [`cybercore-tech/holodrive`](https://github.com/cybercore-tech/holodrive). Follow branch → push → PR → user merge, without stacked PRs. The included GitHub Actions workflow builds, tests, lints, and produces a debug artifact.

Before a release is tagged, hosted CI must build and test the release variant and the release signing secrets must be configured in the repository. The app source is licensed under [Apache-2.0](LICENSE); bundled JetBrains Mono Nerd font files remain under their separate [OFL](docs/JetBrainsMono-OFL.txt).

For signed CI releases, add these repository **Actions secrets** without placing their values in source control: `HOLODRIVE_RELEASE_KEYSTORE_BASE64` (base64 of the existing PKCS#12 release keystore), `HOLODRIVE_RELEASE_STORE_PASSWORD`, `HOLODRIVE_RELEASE_KEY_ALIAS`, and `HOLODRIVE_RELEASE_KEY_PASSWORD`. Push a `vMAJOR.MINOR.PATCH` tag after the workflow is merged; CI runs the server-variant unit tests and release lint, builds and verifies the signed APK, then attaches the APK, SHA-256 file, and install instructions to that GitHub Release. Android's version code is derived from the tag, so each newer release can update the installed app.

JetBrains Mono Nerd font files are bundled under the included [OFL](docs/JetBrainsMono-OFL.txt). The cube is adapted from Holodrive's existing brand asset.
