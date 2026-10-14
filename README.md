# DiPlay — Geely Xingrui E01

[中文](README.zh-CN.md)

CarPlay for the **2021 Geely Xingrui (星瑞)** head unit. The box is an ECARX E01, MediaTek MT6735, GKUI 19, **Android 5.1 (API 22)**. Install it on the car. No jailbreak, no dongle.

Current build: `0.4.1-geely` (versionCode 41). App id: `com.shihab.diplay`.

There is no Apple certification, and no relation to Geely or ECARX. The CarPlay name and icon belong to Apple.

The reference car is software **5068** (factory batch 5066), build `SWFS11G0706H5181.00600`. Check **Settings → About the smart platform → Version** on the head unit. A different version number can still be installed. On another car the risk below may differ, so read it before installing.

## Read this before installing

Wireless uses the Bluetooth already running on the head unit. To carry CarPlay's data path, DiPlay replaces that Bluetooth process.

- It does not write `/system`. **Reboot the head unit and the original process comes back.**
- The app has no restore action. Reboot to put the original process back immediately.
- **Opening DiPlay replaces the process**, whether or not wireless is used, and the replacement stays until the next reboot.

Whether the car's own calls and Bluetooth music still work after the swap **has not been tried on this car**. If something breaks, reboot the head unit. This is an unofficial change. It sits outside warranty and can interfere with a later official update.

To avoid that, use a cable. USB leaves Bluetooth alone.

The full risk note and disclaimer are in the [v0.4.1 notes](docs/release-notes/v0.4.1-geely.md). Read them before deciding to install.

## Tried on this car

**Previous / next** on the steering wheel changes tracks. The head unit turns those two keys into ordinary media keys, and DiPlay receives them. Log from the car: `DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true`.

The wheel has no play/pause key. That position is the **screen-mirroring button** (飞屏). It stays inside the head unit's own UI, so no app receives it.

Wired USB has shown a picture on this car. An iPhone 17 Pro Max draws more current than this USB port supplies, so the session drops and reconnects. A powered hub in between avoids that.

Wireless, and the settings page changed later, are only partly tried on the car. The changelog records code changes. Anything that does not say it was checked on the car still needs a car.

## Install

The head unit must allow APK installation.

Moving from upstream DiPlay, the signing key is different, so this build cannot replace that install. Uninstall first. Uninstalling clears resolution, the audio buffer, steering-wheel key settings and saved Wi-Fi.

A later build of this fork can install over the previous one. Those settings remain.

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V041.apk
```

Without ADB, copy the APK to a USB stick and install it from the head unit's file manager. Allow unknown sources.

## After it opens

Wireless uses the **built-in car hotspot**. The name and password are already on the head unit. If none were entered by hand, DiPlay reads them. It uses 5 GHz when the radio allows it. Turn on Bluetooth and Wi-Fi on the phone, pair it with the car, then tap **Connect phone**.

For a cable, tap **Connect with USB**. Use a data cable in a data port. A charge-only cable does nothing.

**Swipe down with three fingers** on the CarPlay screen to open settings. This version puts every setting back on one page, two across on the wide screen. Resolution, frame rate and icon size take effect only after **Apply and reconnect** during a session. Selecting a value alone leaves the previous one in place.

If the connection stalls, open settings and **Save diagnostic report**. The published package has no upload key, so logs are not sent out on their own. Remove passwords and pairing records before posting a report to a public issue.

## Memory on this head unit

Free memory is often only **26–33 MB**. Spending a large block of it on smoother audio is a poor trade here, so that approach is not included. GKUI has no split screen, so DiPlay stays full screen.

The build still keeps an **Android 4.4 (API 19)** floor, so older head units can be built for. The car actually used is this 5.1 unit. Other cars have not been checked.

## Origin

This line is the Geely fork of [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android), which forks [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay). The base is upstream **v0.2.7**. BYD's cluster, HUD and battery have no matching hardware here, and that code has been removed.

The receiver is based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home and settings UI adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0. The licenses are in `docs/licenses`. Keep those notices when distributing a modified build.

The repository is a clean public source snapshot. Accessory keys and the Android signing key are not in it. The release APK carries an experimental accessory identity taken from public Carlinkit firmware. It is not an MFi identity issued for DiPlay. The private key can be extracted from the package. Whether a future iOS still accepts it, and whether it is fit for wide distribution, is still unsettled. A local source build omits that identity unless asked for. Details are in the [build notes](docs/BUILD.md) and the [credits](docs/THIRD_PARTY_NOTICES.md).

## Further reading

- [This release](docs/release-notes/v0.4.1-geely.md)
- [Changelog](CHANGELOG.md)
- [Build from source](docs/BUILD.md)
- [Install and connect](docs/INSTALL.md)
- [Connection setup](docs/CONNECTION_SETUP.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Validation](docs/VALIDATION.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)
