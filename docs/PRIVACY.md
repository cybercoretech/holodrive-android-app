# Privacy information — Holodrive Photos

Holodrive Photos connects directly to the Holodrive server you configure. Your server address and selected folder determine where the app requests your library and photos. Your password is sent only to that server's login endpoint. If you opt in with **Remember on this device**, the password is encrypted using an Android Keystore key and can only be accessed after fingerprint confirmation. A device token can also be saved, encrypted with Android Keystore, so background uploads can authenticate after the app closes. The token is revocable from the server's Devices page.

The app stores your server address, selected folder and theme locally. Files you share to Holodrive Photos are copied into the app's private storage, then uploaded only after you confirm the destination and queue them. The local staged copy remains until upload succeeds or you dismiss the share. Uploads use the configured Holodrive server's resumable upload API. The app includes no advertising, analytics or developer-operated data collection. It requests Internet access but does not request access to your camera, contacts, or entire device photo library.

When you choose **Save original**, the selected photo is written to the destination you choose through Android's document picker. That copy remains after signing out or uninstalling the app. Remove it through the chosen storage provider if you no longer want it.

**Open Holodrive** hands your server address and current virtual folder to your browser. Browser cookies, history, downloads and site storage are managed separately by that browser. Login credentials are not passed from the app to the browser.

HTTPS provides transport encryption for HTTPS servers. HTTP tailnet connections rely on a separately installed and connected Tailscale client for encrypted transport. The Holodrive server operator and any storage provider you select control their own data handling and logs.

Uncheck **Remember on this device** or sign out to erase saved credentials, cancel queued uploads, and clear the current app session. Sign-out does not revoke the token on the server; revoke it separately from the Devices page. Clear app storage or uninstall to remove other saved preferences and staged files. Exported photos and browser data are managed separately. Server-side copies remain under the server operator's control.

This document describes the development build. Publisher identity, privacy contact and a public policy URL must be supplied before store publication.
