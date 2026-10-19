# Mobile folder handoff and safe sync

## Open a desktop folder on the phone

In the web file browser, **📱 Send to phone** shows a QR code and copies a
`holodrive://open` link containing only the selected virtual folder path.
Scan the code or open the link on an Android device with Holodrive Photos
installed. The app validates the path,
uses its already configured server, and navigates to Files after sign-in. It
does not accept credentials, server overrides, or tokens from the incoming
link.

The app refreshes its current Photos or Files folder every 15 seconds while
the Android activity is resumed. It stops refreshing when the app is not in
the foreground. Existing WorkManager uploads and scheduled sync remain
independent and keep their existing network constraints.

The **Recent activity** action reads the server's shared in-memory feed. It
includes folder changes observed by active watchers, completed uploads, and
sync summaries (including queued uploads, downloads, and conflicts). The feed
retains at most 100 short entries and clears on server restart.

Photos scans recursively within each selected library folder. JPEG EXIF
capture dates are shown and used for ordering when present; other images use
their modified time. EXIF timestamps have no guaranteed timezone, so Holodrive
preserves the camera's local timestamp. The server's existing result and scan
limits still apply.

## Two-way sync

When both sides contain a file and one modified time is clearly newer, that
version is copied. If timestamps are within two seconds but sizes differ,
sync skips both copies, records the relative path, and shows it in **Settings
→ Folder sync** for review. Choose a one-way direction when one side should be
authoritative. The app still never propagates deletions.

This comparison uses modified time and size, not content hashes. It cannot
detect different contents when both files have the same size and close
timestamps. Folder sync is a convenience copy, not a substitute for a separate
backup.
