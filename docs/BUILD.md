# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 25.2.9519653 and the included Gradle wrapper. NDK r25c is intentional: it is the last selected toolchain in this project that can emit the Android 4.4/API 19 native target.

The standard `mobile` application has `minSdk 19` and packages ARM64 and ARMv7 libraries only. The x86 pair is gone: `conscrypt-android` alone carries 4.5 MB of x86/x86_64 shared objects and no head unit uses them. The separate Android Automotive application retains its newer platform requirement.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity, no car hop and no log-upload key. It is what CI builds and it is not a package to install. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Build inputs

Every input that changes what a package contains is a command-line property. None of them is read from
the environment. A variable is inherited, so a shell that happened to export one used to decide the
contents of whatever was built in it — a release built in such a shell carried the car hop and its
payload without anyone asking for them. A retired variable name is now refused outright rather than
ignored, so the surprise cannot come back one indirection further out.

| Property | What it does |
|---|---|
| `-PauthAssetsDir=<path>` | the accessory identity the package carries |
| `-PvendorHopDir=<path>` | the car hop directory. Both packages this project hands out require it |
| `-Pexperimental=on\|off` | whether the package carries the features that are still being tried on the car — and with them the log-upload key |

`-PcloudLogs` is retired. The key is an experimental feature now, and one switch decides both: the two
used to be asked for separately, which is how a package meant to be local came out official. Passing the
old name is refused rather than ignored.

Signing stays on the environment — `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. A password on a command line is readable by anything that
can list processes, and Gradle records `-P` values in its own caches.

## The two packages

`scripts/package-geely.sh` is the only supported way to build a package that can connect. There are
two, and they differ by one build switch:

```sh
DIPLAY_VENDOR_HOP_DIR=/absolute/path/to/the/car-hop scripts/package-geely.sh official
DIPLAY_VENDOR_HOP_DIR=/absolute/path/to/the/car-hop scripts/package-geely.sh local
```

| | official | local |
|---|---|---|
| what it is | the package that is handed out | the same, plus what is still being tried on the car |
| `-Pexperimental` | `off` | `on` |
| log-upload key | no | yes |
| the settings page's experimental bucket | not drawn | drawn |
| same-network Wi-Fi mode | not offered | offered |
| file | `dist/DiPlay-Legacy-Geely-V<nnn>.apk` | `dist/DiPlay-Legacy-Geely-V<nnn>-local.apk` |

Both carry the car hop and the accessory identity, both are release builds, and both use the same
application id and the same signing key — so either installs over the other and its settings are kept.
They are one version: `versionCode` and `versionName` do not differ between them, and neither does the
package name.

The accessory identity comes from `-PauthAssetsDir`, which the script defaults to
`~/.diplay-auth-assets`. Its `offline-mfi/` must hold exactly `identity.pk8` and `certificate.p7b`;
neither file belongs in Git. The build permits those two only when this explicit input is given and
rejects unexpected credential containers elsewhere in APK assets.

Signing comes from `~/.diplay-signing/keystore.properties`, which the script sources, or from the
`ANDROID_*` environment directly. Never commit these values or the keystore. Different signing keys
cannot update an existing installation.

Neither package is built without the hop: `:mobile:assembleStandaloneRelease` fails rather than make
one, because a hop-less package runs the AOSP stack, installs, opens, and connects to nothing.

The adapter stack (`-PadapterDir`) is not in either package. It still mounts into the build by hand for
a run that means to have it, and nothing in the build asks for it.

Before delivery, verify in the APK that `assets/offline-mfi/identity.pk8` and `certificate.p7b` match
the selected local inputs, and that `assets/payload/` holds the car hop's payloads. Update an existing
installation without uninstalling it to preserve its settings.

## Publishing

`scripts/publish-apk.sh` puts the official package on GitHub as a Release, with a SHA-256 checksum. It
runs here and not in CI: the hop is in a private repository that a CI checkout cannot reach, so a
workflow building from a checkout could only ever have published a hop-less package. The script refuses
to publish anything but the official package, and refuses an APK that carries a log-upload key.

The public release source archive corresponds to the tagged source and excludes runtime identities,
signing keys, local configuration and build output. The release APK deliberately contains the
experimental identity described in the notices; it is extractable by recipients. The Android signing
key is not included. The retired `build-beta.py` helper is not used.
