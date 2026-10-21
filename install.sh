#!/bin/sh
# Holodrive Photos for Android: install the latest signed release over adb.
#
#   curl -fsSL https://raw.githubusercontent.com/cybercoretech/holodrive-android-app/main/install.sh | sh
#
# Downloads holodrive-photos.apk and its .sha256 from the latest GitHub release,
# verifies the checksum, then `adb install -r`s it on the connected phone.
# With several devices attached, set ANDROID_SERIAL to choose one; the
# script never guesses. Set HOLODRIVE_PHOTOS_VERSION=vX.Y.Z to pin a release.
# Prefer automatic updates? Add the Cybercore F-Droid repository instead:
#   https://cybercoretech.github.io/fdroid/
set -eu

APP="Holodrive Photos"
REPO="cybercoretech/holodrive-android-app"
ASSET="holodrive-photos.apk"
# SHA-256 of the Holodrive Photos release signing certificate (also in the README
# and on the site). Checked whenever apksigner is available.
CERT_SHA256="02e0bbab51e85a39db95577bb682f52380001a50fcf64013af5b32efa6178e6e"
VERSION="${HOLODRIVE_PHOTOS_VERSION:-latest}"

say() { printf '\033[36m▸\033[0m %s\n' "$*"; }
die() { printf '\033[31m✖ %s\033[0m\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v adb >/dev/null 2>&1 || die "adb is required (Android platform-tools): https://developer.android.com/tools/releases/platform-tools"
if command -v sha256sum >/dev/null 2>&1; then
  sha() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  die "sha256sum or shasum is required to verify the download"
fi

if [ "$VERSION" = latest ]; then
  base="https://github.com/$REPO/releases/latest/download"
else
  base="https://github.com/$REPO/releases/download/$VERSION"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

say "Downloading $APP ($VERSION)…"
curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/$ASSET" "$base/$ASSET" \
  || die "download failed: $base/$ASSET"
curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/$ASSET.sha256" "$base/$ASSET.sha256" \
  || die "checksum download failed"

expected=$(cut -d' ' -f1 "$tmp/$ASSET.sha256")
actual=$(sha "$tmp/$ASSET")
[ -n "$expected" ] && [ "$expected" = "$actual" ] \
  || die "SHA-256 mismatch: expected $expected, got $actual. Not installing."
say "SHA-256 verified: $actual"

signer=""
for c in apksigner "${ANDROID_HOME:-$HOME/android}"/build-tools/*/apksigner; do
  if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then signer=$c; fi
done
if [ -n "$signer" ]; then
  cert=$("$signer" verify --print-certs "$tmp/$ASSET" 2>/dev/null \
    | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  [ "$cert" = "$CERT_SHA256" ] || die "signing certificate mismatch (got ${cert:-none}). Not installing."
  say "Signing certificate verified: $APP release key"
else
  say "apksigner not found: skipping the certificate check (Android still enforces the key on updates)"
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
  devices=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  count=$(printf '%s\n' "$devices" | grep -c . || true)
  unauthorized=$(adb devices | awk 'NR > 1 && $2 == "unauthorized"' | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    [ "$unauthorized" -gt 0 ] && die "the phone hasn't authorized this computer yet: unlock it and accept the USB debugging prompt"
    die "no device found: connect the phone over USB and turn on USB debugging"
  fi
  [ "$count" -gt 1 ] && die "$count devices connected; choose one with ANDROID_SERIAL=<serial> (see 'adb devices')"
  ANDROID_SERIAL=$devices
  export ANDROID_SERIAL
fi

state=$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
[ "$state" = device ] || die "device $ANDROID_SERIAL is not connected or not authorized (see 'adb devices')"
model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
say "Installing on ${model:-device} ($ANDROID_SERIAL)…"
adb install -r "$tmp/$ASSET" >/dev/null || die "adb install failed (an older build signed with a different key must be uninstalled first)"
say "Done. Open $APP on your phone."
