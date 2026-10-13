#!/bin/sh
# Build the Geely E01 APK and name it DiPlay-Legacy-Geely-V<version>.apk
#
# The inputs below are this script's, not the build's: each is translated into a command-line property
# and taken back out of the environment before Gradle runs. The build reads -P only, on purpose — an
# environment variable is inherited, so a shell that happened to have one exported used to decide what
# a package contained.
#
#   DIPLAY_AUTH_ASSETS_DIR   default ~/.diplay-auth-assets    (offline-mfi identity)
#   DIPLAY_VENDOR_HOP_DIR    no default; the car-test build requires it, a release ignores it
#   DIPLAY_CLOUD_LOGS        0 drops the Supabase log-upload key from the APK
#
# The signing key stays a variable: a password on a command line is readable by anything that can list
# processes, and Gradle records -P values in its own caches.
#   ~/.diplay-signing/        keystore.properties + the release keystore
#
# A run in CI always gets the log-upload key dropped; a run here keeps it unless the caller says 0.
#
# Usage: scripts/package-geely.sh [release|debug|both]   (default: release)
#   release — the published package: no car hop, and no way to ask for one from here
#   debug   — the car-test package: requires the car hop, and refuses to build without it
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
kind=${1:-release}
out=${DIPLAY_DIST_DIR:-$root/dist}

# Filename tag keeps only the numeric version: "0.2.7-geely" -> V027
version=$(sed -n 's/.*versionName *= *"\([0-9][0-9.]*\).*".*/\1/p' "$root/mobile/build.gradle.kts" | head -1)
[ -n "$version" ] || { echo "package-geely: cannot read versionName" >&2; exit 1; }
tag="V$(printf '%s' "$version" | tr -d '.')"

: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
auth_dir=${DIPLAY_AUTH_ASSETS_DIR:-$HOME/.diplay-auth-assets}
hop_dir=${DIPLAY_VENDOR_HOP_DIR:-}

# A build made from git is the one that gets published, and it must not carry the log-upload key: a CI
# workspace is a checkout, so whatever a local.properties lying in it says is not what is being
# released. GitHub sets CI for every step; a local compile sets neither, keeps the key, and keeps the
# on-device "generate and upload report" button working for the runs that need it.
cloud_logs=on
if [ -n "${CI:-}${GITHUB_ACTIONS:-}" ] || [ "${DIPLAY_CLOUD_LOGS:-1}" = 0 ]; then
    cloud_logs=off
fi

[ -f "$auth_dir/offline-mfi/identity.pk8" ] || {
    echo "package-geely: $auth_dir/offline-mfi/identity.pk8 is missing" >&2; exit 1; }

# Gradle refuses a stale one of these outright, and that refusal is what makes translating here the
# only way in. This script's own copies are taken above and are not passed on.
unset DIPLAY_AUTH_ASSETS_DIR DIPLAY_VENDOR_HOP_DIR DIPLAY_CLOUD_LOGS
export ANDROID_HOME

mkdir -p "$out"

build_release() {
    sign=${DIPLAY_SIGNING_DIR:-$HOME/.diplay-signing}
    # Local runs read $sign/keystore.properties; CI exports ANDROID_* directly, so the
    # password never has to pass through a sourced file.
    if [ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ] && [ -f "$sign/keystore.properties" ]; then
        set -a; . "$sign/keystore.properties"; set +a
        ANDROID_KEYSTORE_PASSWORD=$storePassword
        ANDROID_KEY_PASSWORD=$keyPassword
    fi
    ANDROID_KEYSTORE_PATH=${ANDROID_KEYSTORE_PATH:-$sign/diplay-release.jks}
    ANDROID_KEY_ALIAS=${ANDROID_KEY_ALIAS:-diplay}
    [ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || {
        echo "package-geely: no release keystore password ($sign/keystore.properties missing?)" >&2; exit 1; }
    [ -n "${ANDROID_KEY_PASSWORD:-}" ] || {
        echo "package-geely: no release key password" >&2; exit 1; }
    export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD ANDROID_KEYSTORE_PATH ANDROID_KEY_ALIAS
    # This is the published package, so the car hop is not passed and cannot be asked for from here.
    # Saying so beats a silent ignore, which is how a release came to be built with one in the first place.
    if [ -n "$hop_dir" ]; then
        echo "package-geely: a release carries no car hop; DIPLAY_VENDOR_HOP_DIR is ignored for it" >&2
    fi
    (cd "$root" && sh gradlew -PauthAssetsDir="$auth_dir" -PcloudLogs="$cloud_logs" :mobile:assembleRelease)
    cp "$root/mobile/build/outputs/apk/release/mobile-release.apk" "$out/DiPlay-Legacy-Geely-$tag.apk"
    echo "→ $out/DiPlay-Legacy-Geely-$tag.apk"
}

build_debug() {
    if [ -z "$hop_dir" ]; then
        echo "package-geely: the car-test package needs DIPLAY_VENDOR_HOP_DIR" >&2; exit 1
    fi
    (cd "$root" && sh gradlew -PauthAssetsDir="$auth_dir" -PcloudLogs="$cloud_logs" \
        -PvendorHopDir="$hop_dir" :mobile:assembleStandaloneDebug)
    cp "$root/mobile/build/outputs/apk/debug/mobile-debug.apk" "$out/DiPlay-Legacy-Geely-$tag-hudtest.apk"
    echo "→ $out/DiPlay-Legacy-Geely-$tag-hudtest.apk"
}

case "$kind" in
    release) build_release ;;
    debug)   build_debug ;;
    both)    build_release; build_debug ;;
    *) echo "package-geely: expected release|debug|both, got '$kind'" >&2; exit 1 ;;
esac
