# Holodrive Photos

A native Android client for [Holodrive](https://github.com/cybercore-tech/holodrive), with a photo gallery, device-token sign-in, and resumable background uploads from Android's share sheet.

**Application ID:** `dev.cybercore.holodrive.photos` (debug: `.debug`). Android 11+, Kotlin / Jetpack Compose / Material 3. This is the first development build, not a published release.

## Using the app

1. Open **Configure your server**. Enter an HTTPS Holodrive origin, or a full Tailscale URL such as `http://blackbox.tail9311b5.ts.net:8768`. Include the port your server actually uses. HTTP IP addresses, LAN hostnames, embedded credentials, and path prefixes are rejected.
2. Connect Tailscale if using a tailnet server. Sign in with the Holodrive password, or create a token on the server's **Devices** page and choose **Device token**. You can also enable background uploads from Settings after password sign-in; the app creates a dedicated device token for this phone.
3. Browse the available drives and folders, then tap **Use this folder**. The folder is remembered on this device. **Folder** in the bottom bar changes it later.
4. Open a subfolder as an album. Photos in the current folder are shown newest-modified first. Search filters filenames in that folder; this is not recursive indexing or EXIF capture-date sorting.
5. Tap a photo, swipe between photos, pinch or double-tap to zoom. **Save original** streams the original to a location selected through Android's document picker.
6. **Open Holodrive** opens the full server GUI in your browser at the current folder. Browser authentication is separate; passwords and session tokens are never passed in the URL.
7. Share one or more files from any Android app to **Holodrive Photos**. Choose the destination folder and add them to the upload queue. Uploads run in the background, show progress notifications, resume interrupted chunks, and retry when the network returns.
8. Select a theme, or follow the server. The theme refreshes on app resume. Low-contrast palette text is adjusted for readability. The included CYBERGRID fallback works when themes are unavailable.

The gallery still displays folders and raster photos; non-photo files, hidden names, and links escaping the shared root are excluded. Uploads accept files shared by other Android apps. Automatic camera backup, file deletion, video playback, and server administration are not included.

## Security and privacy

- HTTPS uses the normal Android certificate trust store; no certificate bypasses.
- Plain HTTP is permitted only for `*.ts.net` in Android's network configuration. Each request is additionally confined to the **exact configured scheme, host, and port**. HTTP DNS results must be within Tailscale's IPv4 / IPv6 ranges. System HTTP proxies and redirects are disabled.
- HTTP itself is not encrypted. For tailnet URLs, Tailscale encrypts the phone-to-server transport using WireGuard. Tailscale must be installed, connected, and authorized separately. The app does not configure Tailscale or the server's access policies.
- The configured server must retain its Host allowlist, password protection, CSRF checks, and login throttle. The app uses session cookies for password sign-in and Bearer authentication for device tokens. It never retries password submissions automatically.
- Passwords are never stored unless **Remember on this device** is checked; they use AES-GCM with an Android Keystore key requiring fingerprint confirmation. A device token is stored only when requested, encrypted with a device-bound Android Keystore key so WorkManager can resume uploads without an interactive prompt. The server currently grants writable tokens access to every writable drive; the app explains this before enabling background uploads. Tokens can be revoked from the server's **Devices** page. Sign-out erases local credentials and cancels queued uploads.
- Shared files are copied into private app storage before they are queued, so temporary Android share permissions do not expire during background work. Staged files remain on device until their upload succeeds or the share is dismissed.
- Preferences store the server address, selected virtual folder, and theme. Android cloud backup/device transfer are disabled. Production builds block screenshots and recent-task previews via `FLAG_SECURE`.
- No analytics, ads, telemetry, contacts, camera, or broad storage permissions. Explicitly saved photos are normal files at the destination chosen by the user, outside the app's session protection.

See [PRIVACY.md](docs/PRIVACY.md) and [VALIDATION.md](docs/VALIDATION.md).

## Build

Use JDK 21, Android SDK 36 / build tools 36.0.0, and the checked-in Gradle wrapper. `local.properties` should contain your SDK path and is ignored by Git.

Debug signing material stays outside the repository at `~/Vaults/android/holodrive-debug.keystore`. To create a local debug key:

```sh
mkdir -p "$HOME/Vaults/android"
chmod 700 "$HOME/Vaults/android"
keytool -genkeypair -keystore "$HOME/Vaults/android/holodrive-debug.keystore" -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
chmod 600 "$HOME/Vaults/android/holodrive-debug.keystore"
./gradlew assembleDebug test lint assembleRelease
```

Android Studio's ordinary `debug` variant is the safe default. It builds the normal signed debug app at `app/build/outputs/apk/debug/app-debug.apk`. Choose `scratchDebug` only when running the isolated synthetic-photo server. `assembleRelease` produces **unsigned** optimized APKs for both variants; release signing and store publishing are not configured. The pinned stack matches CybercoreContinuity; dependency/target upgrade advisories remain visible in lint.

## Isolated emulator testing

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

## Release preparation

The user creates the GitHub repository and chooses visibility; no remote is created automatically. Follow branch → push → PR → user merge, without stacked PRs. The included GitHub Actions workflow builds, tests, lints, and produces a debug artifact once hosted.

Before public distribution: test the real phone over Tailscale, test a separately hosted HTTPS server, review target-SDK/store requirements and dependency updates, select production signing and publisher details, publish a privacy-policy URL/contact, choose the source license, and run hosted CI. No release claim is made until these are complete.

JetBrains Mono Nerd font files are bundled under the included [OFL](docs/JetBrainsMono-OFL.txt). The cube is adapted from Holodrive's existing brand asset.
