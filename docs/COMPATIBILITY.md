# Compatibility

This fork targets the **Geely Xingrui E01** head unit (ECARX E01 / MediaTek MT6735, GKUI 19,
**Android 5.1 / API 22**). It is an independent receiver, not an Apple-certified CarPlay accessory.
The experimental bundled accessory identity is extractable and its future acceptance is not
guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Reference car: Geely E01, Android 5.1 (API 22). The build keeps an **Android 4.4 (API 19) floor** so it still runs on older head units; the Automotive APK remains Android 9+ |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Steering-wheel next/previous confirmed on the reference car with a networked ADB session. Wired and wireless picture, touch and audio are confirmed on the upstream development car, not on this one |
| Other cars | Untested. Every ported piece degrades to the previous behaviour on a head unit that offers neither path, so non-Geely cars are unaffected |
| Wi-Fi | Prefer 5 GHz without an established station connection; explicit 2.4 GHz fallback for firmware that rejects 5 GHz or automatic channel selection |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

**Memory is the binding constraint on this head unit.** `MemFree` sits at 26–33 MB, with `AnonPages`
around 1.05 GB. A feature that is merely *possible* is not necessarily worth porting.

## Legacy Android transport matrix

| Android version | Wired USB | Manual car hotspot | LocalOnlyHotspot |
| --- | --- | --- | --- |
| 4.4 (API 19) | Compatibility USB control/request backend | Yes | Platform unavailable |
| 5–7 (API 21–25) | Framework USB backend | Yes | Platform unavailable |
| 8–9 (API 26–28) | Framework USB backend | Yes | Yes |
| 10+ (API 29+) | Framework USB backend | Yes | Yes |

Wi-Fi Direct is not a mode in this fork: a group needs Android 10, and the head unit this package
serves is API 22. A stored `"WIFI_P2P"` from an install that offered it reads as the car hotspot.

The feature choice remains visible across versions, but a mode that the operating system cannot
provide is mapped to the closest supported backend. The classic View UI, legacy media-button
receiver, pre-channel notifications, pre-23 audio recording/playback, pre-21 codec buffers, multidex
and desugared Java APIs keep the same application flow available on API 19.

The API 19, 21, 24 and 27 emulator matrix validates installation, activity creation, native-library
loading and absence of class-verification/API-level crashes. USB, MFi, Bluetooth handoff, radio
behaviour and sustained audio/video still require physical head-unit and iPhone testing; an emulator
cannot validate those peripherals.

## Steering wheel

Verified on the reference car. The wheel goes through the head unit's own input service, which
re-emits each press as a standard `ACTION_MEDIA_BUTTON`; the fix was the media session declaring
`FLAG_HANDLES_MEDIA_BUTTONS` and a media-button receiver. This wheel has **no play/pause key** —
that position is the head unit's screen-mirroring button, which never enters the Android key
pipeline. The generic accessibility filter and the ECARX vendor key codes are fallbacks for other
head units; GKUI 19 on this car exposes no accessibility settings entry, so that path needs ADB.

## Known limitations

- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the
  cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and
  a lower resolution, then attach a report.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is
  implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The
  capability flag is diagnostic, not proof of group-owner support. Wi-Fi Direct itself requires
  Android 10+ and is therefore unreachable on this car.
- Automatic startup depends on the car's firmware and startup permissions.
- USB requires a data port and correct host/device-role behaviour.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.
- USB permission auto-confirm, the diagnostic-export fallback and the wireless startup diagnostics
  have **not** been exercised on a car yet.
- Source-only debug builds intentionally omit the MFi identity. They run for development and
  diagnostics but cannot authenticate a CarPlay session until the external assets described in
  [the build guide](BUILD.md) are supplied.

Reports record requested and actual frequencies, station association state, fallback failures and
remembered-configuration events. Wi-Fi credentials and protocol payloads are excluded. A successful
hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).
