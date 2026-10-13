# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 25.2.9519653 and the included Gradle wrapper. NDK r25c is intentional: it is the last selected toolchain in this project that can emit the Android 4.4/API 19 native target.

The standard `mobile` application has `minSdk 19` and packages ARM64, ARMv7, x86_64 and x86 libraries. The separate Android Automotive application retains its newer platform requirement.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Build inputs

Every input that changes what a package contains is a command-line property. None of them is read from
the environment. A variable is inherited, so a shell that happened to export one used to decide the
contents of whatever was built in it — a release built in such a shell carried the car hop and its
payload without anyone asking for them. A retired variable name is now refused outright rather than
ignored, so the surprise cannot come back one indirection further out.

| Property | What it does |
|---|---|
| `-PauthAssetsDir=<path>` | the accessory identity the package carries |
| `-PvendorHopDir=<path>` | the car hop directory. The car-test build requires it; a release never takes one |
| `-PcloudLogs=on\|off` | whether the Supabase log-upload key goes into the package |

Signing stays on the environment — `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. A password on a command line is readable by anything that
can list processes, and Gradle records `-P` values in its own caches.

## Local release packaging

Give an external asset directory with `-PauthAssetsDir=<absolute path>`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is given and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit command-line inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

The release APK carries no Supabase log-upload key. `scripts/package-geely.sh` passes `-PcloudLogs=off` whenever `CI` is set, so a package built from a git checkout cannot upload anything and the settings page shows no upload button; a local compile keeps the key and keeps that button. The release workflow refuses to publish an APK that still contains one. To clear it from a package you build here, run the script with `DIPLAY_CLOUD_LOGS=0`.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
./gradlew -PauthAssetsDir=/absolute/path/to/runtime-assets \
    -PvendorHopDir=/absolute/path/to/the/car-hop :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs, and refuses to run without the car hop. `assembleDebug` remains an identity-free source/CI build when the explicit asset input is absent; do not install that output as a standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs, and that `assets/payload/` holds the car hop's payloads. Update the existing test app without uninstalling it to preserve its settings.
