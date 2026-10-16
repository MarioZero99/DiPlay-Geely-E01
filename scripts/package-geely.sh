#!/bin/sh
# Build the Geely E01 packages and name them DiPlay-Legacy-Geely-V<version>[-local].apk
#
#   official  the package that is handed out: the car hop, and none of the features that are still
#             being tried on the car
#   local     the same package plus -Pexperimental=on: the experimental features, and with them the
#             log-upload key
#
# The two are one version and one application id signed with one key, so either installs over the
# other and its settings are kept. What separates them is the one build switch and nothing else; this
# script has no other way to make them differ, which is the point of it.
#
# The inputs below are this script's, not the build's: each is translated into a command-line property
# and taken back out of the environment before Gradle runs. The build reads -P only, on purpose — an
# environment variable is inherited, so a shell that happened to have one exported used to decide what
# a package contained.
#
#   DIPLAY_AUTH_ASSETS_DIR   default ~/.diplay-auth-assets    (offline-mfi identity). Required.
#   DIPLAY_VENDOR_HOP_DIR    no default                       (the car hop). Required.
#
# The signing key stays a variable: a password on a command line is readable by anything that can list
# processes, and Gradle records -P values in its own caches.
#   ~/.diplay-signing/        keystore.properties + the release keystore
#
# Usage: scripts/package-geely.sh [official|local|both]   (default: official)
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
kind=${1:-official}
out=${DIPLAY_DIST_DIR:-$root/dist}

# Filename tag keeps only the numeric version: "0.2.7-geely" -> V027
version=$(sed -n 's/.*versionName *= *"\([0-9][0-9.]*\).*".*/\1/p' "$root/mobile/build.gradle.kts" | head -1)
[ -n "$version" ] || { echo "package-geely: cannot read versionName" >&2; exit 1; }
tag="V$(printf '%s' "$version" | tr -d '.')"

: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
auth_dir=${DIPLAY_AUTH_ASSETS_DIR:-$HOME/.diplay-auth-assets}
hop_dir=${DIPLAY_VENDOR_HOP_DIR:-}

[ -f "$auth_dir/offline-mfi/identity.pk8" ] || {
    echo "package-geely: $auth_dir/offline-mfi/identity.pk8 is missing" >&2; exit 1; }
# Neither package is built without the hop: one without it runs the AOSP stack and connects to nothing,
# and handing that out is the failure this script exists to make impossible.
[ -n "$hop_dir" ] || {
    echo "package-geely: set DIPLAY_VENDOR_HOP_DIR; both packages carry the car hop" >&2; exit 1; }

# Gradle refuses a stale one of these outright, and that refusal is what makes translating here the
# only way in. This script's own copies are taken above and are not passed on. DIPLAY_CLOUD_LOGS and
# DIPLAY_ADAPTER_DIR are in the list because a shell that still exports one would otherwise be refused
# by the root build file; the log-upload key is -Pexperimental's now, and no package carries an adapter.
unset DIPLAY_AUTH_ASSETS_DIR DIPLAY_VENDOR_HOP_DIR DIPLAY_CLOUD_LOGS DIPLAY_ADAPTER_DIR
export ANDROID_HOME

mkdir -p "$out"

build() {
    experimental=$1
    suffix=$2
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
    (cd "$root" && sh gradlew -PauthAssetsDir="$auth_dir" -PvendorHopDir="$hop_dir" \
        -Pexperimental="$experimental" :mobile:assembleStandaloneRelease)
    cp "$root/mobile/build/outputs/apk/release/mobile-release.apk" "$out/DiPlay-Legacy-Geely-$tag$suffix.apk"
    echo "→ $out/DiPlay-Legacy-Geely-$tag$suffix.apk"
}

case "$kind" in
    official) build off "" ;;
    local)    build on "-local" ;;
    both)     build off ""; build on "-local" ;;
    *) echo "package-geely: expected official|local|both, got '$kind'" >&2; exit 1 ;;
esac
