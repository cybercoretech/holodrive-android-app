# Validation — 2026-10-13

## Device-token and share-upload milestone — 2026-10-15

The debug variants compile with `./gradlew :app:assembleDebug`. This build
includes device-token authentication, an Android share target, staged file
copies, and a WorkManager resumable upload queue. The upload flow has not yet
been exercised end to end against the scratch server or on the physical phone;
the baseline emulator results below predate this milestone.

## Build and automated checks

JDK 21, Gradle 8.13, Android SDK/target 36, minimum SDK 30. The application and Kotlin/Compose plugin versions match CybercoreContinuity.

Passed: `./gradlew assembleDebug test lint assembleRelease`. All 32 test executions passed (eight API/security tests in each of the normal and scratch variants and both build types). Lint reported 0 errors and 0 warnings; ten upgrade advisories are informational.

The suite contains eight API/security tests, run in the normal and scratch variants for debug and release:

- Reject unencrypted ordinary HTTP addresses, embedded credentials and non-origin URLs; accept HTTPS and full tailnet hostnames.
- Restrict HTTP resolution to Tailscale's IPv4 and IPv6 ranges.
- Send the login cookie and logout CSRF token; clear the cookie after logout.
- Notify the UI and clear the session on HTTP 401.
- Make exactly one request on a rate-limited login.
- Block redirects and requests to a different origin before credentials can leave the selected server.
- Preserve Unicode/special-character virtual paths and original download bytes.
- Exclude hidden names, escaping links, SVG and non-photo files from the gallery.

Lint treats warnings as errors. Dependency/target upgrade advisories remain informational because this build deliberately matches the reference application's pinned stack. In Android Studio, `scratchDebug` targets the isolated fixture server; the ordinary `debug` variant uses normal network resolution and screenshot protection.

## Emulator checks

Tested on **Blackbox_API_36**, headless, using adb accessibility inspection and emulator input only. No desktop pointer/keyboard automation. The real Holodrive binary ran separately on `127.0.0.1:18768` with its own password hash, synthetic images, configuration and cache. Production password attempts and production file changes: **none**.

Verified:

- Password login against the real server API, with authenticated thumbnails and original images.
- Initial folder chooser, drive navigation, selection of a subfolder as the library, and retained folder choice after app-process restart.
- With password remembering disabled, password entry is required after process restart.
- Photo grid, two nested albums, correct exclusion of a text file/hidden file, and empty-album state.
- CYBERGRID theme selection, readable gallery text, and system bars.
- Full-screen viewer, swipe paging, double-tap zoom/reset. The underlying gallery is removed while the viewer is shown.
- Native save-location picker. The resulting JPEG's bytes match the scratch-server original exactly.
- Offline error/retry after stopping the scratch server.
- Server restart invalidates the session and returns the app to the password screen, with an explanatory message.
- Full web GUI launches in an external browser with the configured host and selected virtual folder.
- Explicit sign-out clears the gallery and returns to the password screen.
- Password remembering now uses an opt-in Android Keystore AES-GCM credential protected by biometric authentication; biometric save/restore still needs device validation.
- The normal `debug` variant sets Android's `SECURE` window flag. `scratchRelease` also keeps screenshot protection and disables the loopback override.

Screenshots were captured only from `scratchDebug` with synthetic fixtures. See [login](screenshots/01-login.png), [folder chooser](screenshots/02-folder-picker.png), [gallery](screenshots/03-gallery.png), [viewer](screenshots/04-viewer.png), [zoom](screenshots/05-zoom.png), [empty album](screenshots/06-empty-album.png), [settings](screenshots/07-settings.png), and [expired session](screenshots/08-session-expired.png). The normal build's screenshot protection was checked separately.

## Remaining release validation

- The user explicitly deferred S25 Ultra installation and real-phone Tailscale testing; the APK is prepared for later.
- Real HTTPS-server interoperability and certificate-error UI have not been exercised end to end. The app uses OkHttp/Android default certificate validation; it supplies no custom TLS trust manager.
- Two-finger pinch behavior is implemented with Compose transform gestures but has not been exercised with physical multi-touch hardware. Double-tap zoom and single-finger paging were tested on the emulator.
- Device rotation, unusually large libraries, unusual image codecs and large-font accessibility need broader device coverage before a public release.
- No GitHub remote/PR exists yet. A CI workflow is supplied, but hosted CI has not run.
- Release signing, publisher details, public privacy-policy contact/URL and source license are pending. The optimized release APK is unsigned and is not a store release.
