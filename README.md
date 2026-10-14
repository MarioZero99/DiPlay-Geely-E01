# DiPlay — Geely Xingrui E01

[中文](README.zh-CN.md)

CarPlay for the **2021 Geely Xingrui (星瑞)** head unit. The box is an ECARX E01, MediaTek MT6735, GKUI 19, **Android 5.1 (API 22)**. It goes on the car. No jailbreak, no dongle.

This build is `0.4.1-geely` (versionCode 41). The app id is `com.shihab.diplay`.

Apple has not certified it. Geely and ECARX have nothing to do with it. The CarPlay name and icon belong to Apple.

The car I was working on is software **5068** (the factory batch was 5066), build `SWFS11G0706H5181.00600`. Check yours under **Settings → About the smart platform → Version**. A different number still installs. The risk below may land differently on your car. You decide.

## Read this before you install

Wireless borrows the Bluetooth the head unit is already running. To carry CarPlay's data path, DiPlay takes over the Bluetooth process that is running.

- It does not write `/system`. The system partition stays put. **Reboot the head unit and the original process comes back.** You don't do anything else.
- There is no undo in the app. Reboot if you want it back now.
- **Opening DiPlay is enough.** It happens whether or not you use wireless this time, and it stays until the next reboot.

Whether the car's own calls and Bluetooth music still work with that swapped process **has not been tried on this car**. If something breaks, reboot the head unit. This is unofficial. It sits outside warranty, and it can get in the way of a later official update.

If you don't want that, use the cable. USB leaves Bluetooth alone.

The full note is in the [v0.4.1 release notes](docs/release-notes/v0.4.1-geely.md). Read it, then decide.

## What I have actually tried on this car

**Previous / next** on the wheel changes tracks. The head unit turns those presses into ordinary media keys, and DiPlay catches them. I pressed them in the car. The log is `DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true`.

There is no play/pause on this wheel. That spot is the head unit's screen-mirroring button (飞屏). It stays in the vendor UI. No app can catch it.

USB has put a picture on this screen. An iPhone 17 Pro Max wants more current than this port gives, so the session drops and comes back. A powered hub gets around it.

Wireless, and the settings page that changed later: some of it has been in the car, some has not. The changelog is what changed in the code. If it doesn't say it was tried on the car, try it yourself.

## Installing

The head unit has to allow APK installs.

Coming from upstream DiPlay, the signature is different, so this will not install over that copy. Uninstall first. Resolution, the audio buffer, wheel-key roles and saved Wi-Fi go with it.

Updating along this fork can install over the previous build. Those settings stay.

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V041.apk
```

Without ADB, copy the APK to a USB stick and install it from the car's file manager. Allow unknown sources.

## Once it's open

Wireless uses the **built-in car hotspot**. The name and password are already in the head unit. If you didn't type your own, DiPlay reads them. It uses 5 GHz when the radio allows it. Leave Bluetooth and Wi-Fi on in the phone, pair it with the car, then tap **Connect phone**.

For a cable, tap **Connect with USB**. Use a data cable in a data port. A charge-only cable does nothing.

**Swipe down with three fingers** on the CarPlay screen to open settings. This version puts them back on one page, two across, because the screen is wide. Resolution, frame rate and icon size need **Apply and reconnect** while a session is up. Selecting a value leaves the old one in place.

If it gets stuck, use **Save diagnostic report**. The published package has no upload key, so logs don't leave the car on their own. Wipe passwords and pairing records before you paste a report into a public issue.

## This head unit is tight on memory

Free memory usually sits around **26–33 MB**. Spending a big chunk of that on smoother audio is a bad trade here, so that path is not in. GKUI has no split screen, so DiPlay stays full screen.

The build still keeps an **Android 4.4 (API 19)** floor, so older head units can be built for. The car in front of me is 5.1. I haven't sat in another one.

## Where this comes from

This line is the Geely fork of [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android), which forks [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay). The base is upstream **v0.2.7**. BYD's cluster, HUD and battery have no counterpart on this car, and that code is gone.

The receiver is based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home and settings UI adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0. Those licenses are in `docs/licenses`. If you ship a modified build, leave the notices in.

The repository is a clean public source snapshot. Accessory keys and the Android signing key stay out of it. The release APK carries an experimental accessory identity taken from public Carlinkit firmware. It is not an MFi identity issued for DiPlay. The private key can be extracted. Whether a future iOS still accepts it, and whether it should be handed out widely, is still open. A local source build leaves that identity out unless you ask for it. Details are in the [build notes](docs/BUILD.md) and the [credits](docs/THIRD_PARTY_NOTICES.md).

## More

- [This release](docs/release-notes/v0.4.1-geely.md)
- [Changelog](CHANGELOG.md)
- [Build from source](docs/BUILD.md)
- [Install and connect](docs/INSTALL.md)
- [Connection setup](docs/CONNECTION_SETUP.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Validation](docs/VALIDATION.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)
