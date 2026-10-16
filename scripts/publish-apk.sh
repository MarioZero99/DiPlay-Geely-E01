#!/bin/sh
# Put the official package on the public line as a GitHub Release.
#
# The package is built here rather than in CI, and that is the whole reason this script exists: what
# is published carries the car hop, the hop lives in a private repository, and a CI checkout has no way
# to reach it. So the build happens where the hop is, and this uploads what it made. The release
# workflow that used to do it from a checkout is gone — it could only ever have published a package
# with no hop in it, which is not a package anyone can install.
#
# The official package is the only one this publishes. The local one carries the log-upload key, and a
# Release is a thing anyone can download.
#
# Usage: scripts/publish-apk.sh [--dry-run]
#
# Run after `scripts/package-geely.sh official`. Needs gh, authenticated, with write access to the
# repository — and creating a Release is refused by this machine's permissions until you allow it.

set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
out=${DIPLAY_DIST_DIR:-$root/dist}
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"

dry_run=false
if [ "${1:-}" = "--dry-run" ]; then dry_run=true; fi

# The tag is the whole version name; the file name keeps only its numbers. Both are derived from the
# same line so the two cannot drift apart.
name=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$root/mobile/build.gradle.kts" | head -1)
[ -n "$name" ] || { echo "publish-apk: cannot read versionName" >&2; exit 1; }
tag="v$name"
numeric=$(printf '%s' "$name" | sed -n 's/^\([0-9][0-9.]*\).*/\1/p')
[ -n "$numeric" ] || { echo "publish-apk: versionName '$name' does not start with a number" >&2; exit 1; }
apk="$out/DiPlay-Legacy-Geely-V$(printf '%s' "$numeric" | tr -d '.').apk"
notes="$root/docs/release-notes/$tag.md"

[ -f "$apk" ] || {
    echo "publish-apk: $apk is missing. Run scripts/package-geely.sh official first." >&2; exit 1; }
[ -f "$notes" ] || {
    echo "publish-apk: $notes is missing. Write the release notes in the same commit as the version bump." >&2; exit 1; }
if gh release view "$tag" >/dev/null 2>&1; then
    echo "publish-apk: $tag already has a Release; nothing to publish." >&2; exit 1
fi

apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
report=$("$apksigner" verify -v --print-certs "$apk")
echo "$report"

fail() { echo "publish-apk: $1" >&2; exit 1; }

# Signed the way an Android 4.4 head unit insists on, with this project's key and not another.
echo "$report" | grep -q 'v1 scheme (JAR signing): true' \
    || fail 'no v1 (JAR) signature — Android 4.4 head units refuse this APK'
echo "$report" | grep -q 'v2 scheme (APK Signature Scheme v2): true' \
    || fail 'no v2 signature'
echo "$report" | grep -q 'certificate DN: CN=DiPlay Geely E01' \
    || fail 'signed with an unexpected certificate'

# It installs and it can connect: the identity is what the iPhone is shown.
listing=$(unzip -l "$apk")
echo "$listing" | grep -q 'assets/offline-mfi/identity.pk8' \
    || fail 'no accessory identity in the APK — it would install but never connect'
echo "$listing" | grep -q 'assets/offline-mfi/certificate.p7b' \
    || fail 'no accessory certificate in the APK'

# This package is the official one, so the hop must be in it. Its absence is the failure the old CI
# job was built to guarantee, and it is the one thing about this APK that cannot be seen from the
# outside: a hop-less package installs, opens, and connects to nothing.
echo "$listing" | grep -qE 'assets/payload/' \
    || fail 'no car hop payload in the APK — this is not the official package'
echo "$listing" | grep -qE 'lib/.*/libpayload_crypto\.so' \
    || fail 'no car hop native library in the APK'
unzip -p "$apk" 'classes*.dex' | grep -a 'carhop/VendorCarHop' >/dev/null \
    || fail 'no car hop implementation in the APK — this is not the official package'

# And the log-upload key must not be. It is an experimental feature; the official package is built
# without -Pexperimental, and this is the check that it was.
if unzip -p "$apk" 'classes*.dex' \
    | grep -aE 'sb_publishable_|eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9' >/dev/null; then
    fail 'the APK carries a log-upload key — the official package must not'
fi

# The name is the one package-geely.sh writes, and it must not be a local build wearing it.
case "$(basename "$apk")" in
    *-local.apk|*hudtest*) fail "$(basename "$apk") is not the official package name" ;;
esac

(cd "$(dirname "$apk")" && shasum -a 256 "$(basename "$apk")" > SHA256SUMS.txt && cat SHA256SUMS.txt)

if [ "$dry_run" = true ]; then
    echo "publish-apk: dry run — verified, nothing uploaded. Would create $tag with:"
    echo "  $apk"
    echo "  $(dirname "$apk")/SHA256SUMS.txt"
    exit 0
fi

gh release create "$tag" \
    --title "DiPlay Geely E01 $tag" \
    --notes-file "$notes" \
    "$apk" \
    "$(dirname "$apk")/SHA256SUMS.txt"
