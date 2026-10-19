# Changelog

This file records **this fork's** releases only. Upstream DiPlay maintains its own changelog in
[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).

**Base:** upstream DiPlay **v0.2.7**, via `programmerguohuajing/DiPlay-Legacy-Android`.
**Upstream alignment:** every entry below states the upstream DiPlay version it was checked
against. The base never moves — it is what makes this fork installable on old head units — so the
alignment line, not the base line, is what says how current a release is.
**Target:** Geely Xingrui E01 (ECARX E01 / MediaTek MT6735, GKUI 19, **Android 5.1 / API 22**).

> 4.1 之后的改动按「新增 / 改进 / 优化 / 修复」汇总在
> [CHANGELOG-4.1-to-HEAD.md](CHANGELOG-4.1-to-HEAD.md)；这份文件保留逐版本的发版记录。

---

## 0.4.3-geely — 2026-10-19

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.5.

**Same Wi-Fi is withdrawn for now.** Joining a network that already exists is not offered in this
release: it has not worked on the car yet and is still being worked on. Wireless is the car hotspot
again, which is unchanged. A head unit that had same Wi-Fi selected comes up on the car hotspot after
the update, so nothing has to be changed by hand.

**The iPhone identification step is fixed.** A protocol mismatch could make the phone refuse the
identification exchange, which showed up as a connection that failed, or as retries that never got
past it. Wired and wireless both go through that step, so both benefit.

**Connecting can be cancelled.** The disconnect control is on the stage while an attempt is running,
not only after a session exists; during a connect it says Cancel. A failed attempt that stays in its
phase no longer leaves the button greyed out for good: it comes back after 1.2 s.

**The first launch asks which way.** The app could not tell "wireless was used last" from "nobody has
chosen yet", so a fresh install went wireless without anyone choosing. With nothing saved, the main
control now says "Choose connection mode" and offers wired and wireless side by side. Wired no longer
shows the wireless readiness lines or the hotspot note; a head unit with no USB host says why and
greys the wired control.

**Smaller.** The settings drawer takes 42% of the screen width (680–800 dp here) instead of a fixed
520 dp. The 21 audio channels are a 3×7 grid that fits the card, so every cell is reachable. The test
tone no longer stutters on every press, and shuts down cleanly when the app exits.

## 0.4.2-geely — 2026-10-18

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.5.

**The cockpit is one screen again, drawn for 1920×720.** The home page was a single column that
scrolled on a 24:9 panel and left the driver hunting for the one control they wanted. It is three
zones now: a slim dock down the left (home, audio, settings, back to the head unit's own desktop,
exit), the connection itself in the middle — status, paired phone, the one button, the reason an
attempt was refused — and the wireless and audio switches on the right. Leaving for the head unit's
desktop is not quitting: the session and reconnects keep running. Settings open as a half-screen
drawer from the right instead of replacing the page, and Back closes the drawer first. Exit asks
once, on every page. The instrument map card now stays up when no second display is detected, and
says what it is waiting for. Three skins (Dawn white, Sky blue, Night) and a day/night switch that
follows the head unit, a clock (18:00–06:00), or the driver.

**The projection has a quick bar in it.** Three fingers sliding down over the CarPlay picture drop a
bar without leaving it: disconnect, the navigation audio channel (0–20, applied to the next
connection), day/night, and exit behind a second tap within three seconds. It folds after six
seconds idle, or twenty with the channel strip open, and a tap outside closes it without passing the
touch to the phone.

**Wireless can join the driver's own network instead of making one.** The Wi-Fi Direct route this
fork could not use is gone; what replaced it is the mode the driver actually has: the head unit joins
a network that already exists — an Android phone's hotspot, a router, or an iPhone's Personal Hotspot
— with the iPhone on the same one. Nothing is typed in: the network's name comes from the connection
the head unit is on, and its security type and key from the configuration the head unit saved when
the driver joined it. One radio cannot be a station and an access point at once, so the car's own
hotspot is switched off for this mode — only off, never on — and an attempt that fails leaves it off
until the driver turns it back on or connects another way.

**Wireless.** The car's hotspot can be switched on when DiPlay starts, behind a setting that is off
by default. Releasing that hotspot now hands the radio back: the station side is switched on and
waited for, instead of the join that followed waiting for a network nothing was looking for. A
failure to read the head unit's saved networks no longer reads the same whether the app was refused
permission or the network was never saved there — the two want opposite things done about them. The
wait for a usable network now reports what it is still missing as it goes, so a report says where it
stopped instead of only that it ran out of time. A hotspot DiPlay is about to release is no longer
drawn as a refusal that sends the driver to turn it off by hand. Failure reasons on this route reach
the diagnostic report again.

**Diagnostics can be written to a USB stick in one tap.** The system file picker would not open on
some head units, so the report goes to the stick first, then the head unit's Downloads directory,
then the app's own files, and says which one it used. The head unit's own storage is no longer
mistaken for a stick. The report carries the last few connection attempts, so a report shows where
an attempt stopped rather than only that it failed.

**Wheel keys.** Previous/next no longer take the head unit's own keys with them. DiPlay was asking
for a wider set than it handled, so play/pause stopped working in the car, and the voice key was
consumed at the window layer for nothing. Only the keys the driver actually reports are taken now.

**Size and cost.** The package is about half the size of 0.4.1 — R8 and resource shrinking, where
before the dex alone was 13.6 MB. The home page and the drawer update in place instead of being
rebuilt, the animations stop when they cannot be seen, and video playback puts less on a CPU that
has 26–33 MB of free memory to work with.

**⚠️ Wireless changes the head unit's own Bluetooth.** Unchanged from 0.4.1, and still true: to
carry CarPlay's data path, DiPlay takes over the Bluetooth process the head unit is running, **at
every app start**, not only when wireless is used. `/system` is not written, a head unit restart
puts the original back, and there is no undo in the app. Whether the head unit's own calling and
Bluetooth audio still behave against it **has not been checked on the car**. The firmware version
this was built against and the disclaimer are in `docs/release-notes/v0.4.2-geely.md`.

## 0.4.1-geely — 2026-10-14

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.5.

**The settings are one page again.** The hub that went in after 4.0 sent the driver through four
entry cards to guess which bucket a setting had been filed under. All thirteen are on the settings
page now, the four bucket headings and the wireless route's `1 · 2 · 3` kept, and they are drawn two
to a row — this head unit is 1200 dp wide, so one column was several screens of scrolling.

**Wireless.** A head unit with no radio that could carry the leg is told so, rather than handed a
list of things to fix that no action of the driver's could fix. Refusing the hotspot no longer takes
the car's Wi-Fi with it: Wi-Fi was dropped unconditionally on the retry, so a hotspot the firmware
had refused left the driver's own network — and Wi-Fi debugging — down, with nothing to put it back.
A superseded attempt no longer leaves its own discovery responder behind; one process holds one.
Failure reasons on this route now reach the diagnostic report.

**Wired.** The USB receive queue had no ceiling anywhere in the stack, on a head unit that runs with
26–33 MB free. A peer that stops reading can now fill 1 MiB before the connection is torn down, by
the same route an RST from the peer takes. A phone showing its authorisation prompt is no longer
reported as a write fault.

**Removed.** BYD's HUD, battery and charging output, which have no counterpart on this head unit.

**⚠️ Wireless changes the head unit's own Bluetooth.** To carry CarPlay's data path, DiPlay takes
over the Bluetooth process the head unit is running — and it does so **at every app start**, not
only when wireless is used. `/system` is not written, a head unit restart puts the original back,
and there is no undo in the app. Whether the head unit's own calling and Bluetooth audio still
behave against it **has not been checked on the car**. The firmware version this was built against
and the disclaimer are in `docs/release-notes/v0.4.1-geely.md`.

## 0.4.0-geely-rc — 2026-10-10

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.5.

### Connection page

- **The page is laid out for the head unit's 24:9 screen.** Status, a four-step progress row and the
  one action are on the left; the USB bus and the handshake log are on the right; the transport
  choice, Settings and the way home are in a bar at the top. Nothing scrolls, so the buttons are
  reachable without scrolling — on a 1920×720 panel the old centred column was taller than the
  screen. The status line wraps to two lines: a failed attempt arrives as a whole sentence, and a
  title that cut it off mid-clause told the driver nothing.
- **The progress row names the step the attempt is actually on.** The rung was worked out inside the
  host activity, which knew the wired statuses and reported every other one as the last rung — so an
  attempt still opening its data paths could read as finished. `ConnectionPlan` owns the mapping now,
  once, for both transports, and it is exhaustive over the controller's statuses: a status the
  controller gains has to be placed on both ladders, or `:shared` stops compiling.
- **Choosing the iPhone, and what a refused attempt was missing, both happen on the page.** Each was
  an `AlertDialog` that had to be dismissed before the next step was readable. The phone is a
  selected/primary pair of buttons, and a refused connect shows its reasons in a card on the page
  rather than over it.
- **Plugging a USB device in no longer starts anything.** `CarPlayHostActivity` claimed
  `USB_DEVICE_ATTACHED` with a device filter, so Android opened the projection screen the moment a
  device matched and the session started on its own — an iPhone cable went straight to wired
  CarPlay. The filter is gone; a connection begins only from the connect button. The wired path is
  unchanged once it is asked for.
- **A wired fault now says what to do about it.** The link assessment already worked out whether
  nothing was on the bus, whether the attached device was not an iPhone, whether the plug was loose,
  or whether the supply dipped; that verdict is shown as the primary button's advice instead of every
  fault ending at "Reconnect".
- **The car's own hotspot is read from the head unit instead of asked for.** The only wireless mode
  left on this fork's API floor is the car's own hotspot, and it needs a name and key before a
  connection can start — a freshly installed DiPlay has neither, so it used to reach the attempt
  blank and fail there. The head unit already holds both, so they are taken from it when the driver
  has not entered their own; entered values still win. The home screen and connection setup read the
  same pair, so the hotspot they name is the one the connection will use, and setup no longer stops
  the driver to retype details the car had already supplied. An incomplete runtime config now says so
  on the page rather than taking the screen down.
- **A car hotspot taken from the head unit is announced with the security its key implies.** The
  security mode was derived from the stored passphrase, which on a fresh install is empty — so the
  car's WPA2 key was announced as an open network. Both the settings page and the hotspot manager
  hold that mode against the live access point, and both refused it: the first would not save a key
  it had been told was open, the second rejected the access point outright. The mode now follows the
  passphrase the connection actually uses, so a secure car hotspot stays secure and an open one stays
  open. The handshake line names the mode it adopted, since it is the one part of the pair the car
  does not supply.

### Wired link

- **A pulled cable is reported as a pulled cable.** `ACTION_USB_DEVICE_DETACHED` was handled, but not
  for the iPhone, so a wired session learned the phone was gone when a USBMUX read eventually failed,
  and the page reported it in USB-layer terms long after it had left. The controller listens now.
  Mid-session it fails at once and names the cable, and the existing reconnect path does the rest;
  while searching it restarts the search, so a permission poll already running cannot wait out its
  whole timeout for a phone that is no longer there.
- **"Waiting for iPhone" says what is wrong with the cable.** The page had one sentence for "no
  iPhone". It now separates an empty bus — port, charge-only cable, or the plug's orientation — from
  a bus holding something that is not a phone. The verdict goes to the handshake log after six
  seconds of empty searching, and only when it changes.
- **A CarPlay re-enumeration is not a re-plug.** The configuration request asks the iPhone to leave
  the bus and come back, so the phone detaches on every connection that works. Counting those as a
  drop would have called a working cable loose after three good connections. Only a detach in a phase
  where nothing asked the phone to leave is counted, and only a return inside five seconds counts — a
  slower one is a person walking to the phone. The count is kept for the life of the process rather
  than on the controller, because a session that drops mid-stream is handled by rebuilding the
  controller, which would have destroyed the counter that was counting the drop.
- **Waiting for the CarPlay configuration has a way out.** That rung is ended by the attach that
  brings the re-enumerated phone back, and a cable pulled while waiting for it left the rung waiting
  forever for an attach that was never coming. It carries a ten-second deadline now, and expiry fails
  the attempt instead of searching again — the phone is off the bus for the whole of a healthy
  re-enumeration, so a shorter watch would read a slow one as a pulled cable, and restarting from
  there would reset the count that bounds how often the phone may be asked to leave the bus.
- **An iPhone 17 Pro Max draws more than the head unit's USB port supplies, and that is what its
  dropouts are.** Confirmed on the car: the session ends and reconnects because the phone cannot get
  the current it asks for. Neither the cable nor the app is the cause, nothing in this release
  changes it in either direction, and a powered hub is the way around it.

### Wireless

- **A held port 7000 no longer ends the attempt.** 7000 is what AirPlay conventionally uses, and on a
  head unit something else can be holding it — a DiPlay process that did not exit, or another app —
  so a failed bind used to fail the whole wireless attempt. `AirPlayPorts` takes the requested port
  when it is free and an ephemeral one otherwise. The iPhone is told the port in the Bonjour record
  and in the iAP2 Wi-Fi configuration, so any free port still reaches us.
- **A responder that will not come up names the socket.** `CarPlayBonjour` reports the port, the
  address and the family when an interface's responder cannot start, in place of a bare
  `BindException` that said none of them.

### Diagnostics

- **Every transport, media and network line reaches the session log.** `android.util.Log` became
  `DiagLog` across 102 call sites in 17 files: the logcat entry stays, and the same text is offered
  to `DiagSink`. Those lines went to logcat and nowhere else, and on a head unit with no adb they are
  the only description of why a link failed.
- **Lines written before a log exists are held rather than dropped.** `DiagSink` queues up to
  `PENDING_CAPACITY` (256) lines and flushes them the moment a writer attaches. The transport and
  media threads report before the host page opens its log and outlive it, so the first seconds of a
  run — the ones that say why it failed — were the ones being thrown away.
- **A crash is written into the session log.** `DiagCrashHandler` is installed by both pages in
  `onCreate`, and it wrote through a path only `SessionLogFile.reset()` creates — which needs a
  successful bootstrap. A crash before the first session, the clean-install case and the one most
  worth having, was written nowhere at all.
- **The report says what it lost.** `DiagnosticCounters` counts queue drops, write failures and sink
  overflows. A full disk, a full queue and a closed writer all produced the same result as a quiet
  run, which is what made a missing line unreadable.
- **The byte budget is spent on signal lines, not on repeats.** `DiagnosticDigest` keeps signal lines
  from every run first, then the newest run in full, then older runs; consecutive repeats cost one
  line and a count, and whatever does not fit is stated rather than dropped quietly. The field
  reports were four near-identical runs of "found none": the budget went on saying it four times and
  the line that explained the failure was never in the file. The budget is `BUDGET_BYTES` (1 MiB),
  the upload cap, so what is saved is what would be sent.
- **The header survives the budget, and the report reads in order.** The header — which build, which
  car — is kept out of what the budget can spend, and `setupErrorDetail` carries the exception behind
  an auth failure into it, where until then it existed only in logcat. `compose()` numbered lines
  with a per-section index, so the final sort grouped every run's first line together and read the
  timestamps backwards; one running index across the sections is chronological order.
- **A withheld line is flagged rather than removed.** `DiagnosticRedactor` marks a line it withheld
  instead of dropping it whole, so a report cannot read as a run in which the line never happened.
- **The report can be got off the car.** It was written only to the app's own external directory,
  which the head unit's file picker does not reach and a stick cannot see — so the run that needed
  handing over was the one that could not be. It is copied to `Downloads/DiPlay`, to every external
  volume the context reports, and to the USB mount roots this head unit uses, with the app's private
  directory as the fallback. `WRITE_EXTERNAL_STORAGE` is declared with `maxSdkVersion 28` so the
  Android 4.4–8.1 units can write those copies at all.
### Languages

- **The picker offers the two languages this port actually translates.** Arabic, Russian and Spanish
  came from upstream and had already fallen behind — the strings added since 0.3.5 were English on
  those screens anyway. Their string files and their picker entries are gone.

## 0.3.5-geely-rc — 2026-10-08

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.4:
DiPlay `main` `5e58b4b` (2026-10-07), tag **v0.2.13**. The porting conclusions recorded there still
apply.

### Wired session

- **A USB read that finishes as the timeout fires is kept.** On API < 26, `awaitUsbRequest`
  cancels the request from a timer, and that cancel can race a transfer that already completed.
  The timeout is thrown only when the deadline expired and the buffer is still empty. Wired iAP2
  runs with acknowledgements disabled, so dropping that chunk would tear the mux down. The NCM
  read still uses the untimed wait, so a timed-out request stays queued. Not verified on the car.
- **A live NCM bulk-out failure ends the session after three in a row, and does not clear the
  endpoint.** Before the phone has sent a frame, a failed bulk OUT is still the expected
  pre-session NAK and is retried. After that, consecutive failures are counted and the session
  fails at three. `CLEAR_FEATURE(ENDPOINT_HALT)` is not sent on those failures: it resets the data
  toggle to DATA0 even when the endpoint was not halted, and Android's control transfer is not the
  kernel halt-clear. Reopening the device still clears a real halt. Not verified on the car.

### Connection

- **Wireless CarPlay is offered on the E01 again.** The FS11GQJ gate is gone.
  `loadWirelessEnabled` returns the stored preference (default true) instead of forcing it off.
- **The default connection applies only when DiPlay opens by itself.** Last used, wireless, or USB.
  Manual buttons still start the mode that was tapped. Auto-connect stays off unless the user turns
  it on, and it still runs only on the first launch with no setup error, no background session, and
  no page extra.
- **The home screen lists the methods this head unit can call.** USB when the device has a USB host.
  Car hotspot only when the Wi-Fi service, `setWifiApEnabled`, a Bluetooth adapter, a Bluetooth
  state read, and `createRfcommSocketToServiceRecord` all answer, and Bluetooth is on. Wi-Fi Direct
  only on Android 10 and later. If a call cannot be made, car hotspot is left off that line and the
  reason is shown on the home screen, in connection setup, and in the in-session Wi-Fi settings.
  Bluetooth merely off, or a missing Bluetooth permission, keeps the setup page and explains why.
  The probe does not turn a radio on or open a socket. Not verified on the car. Wired USB is still
  the only path proven on this car.

---

## 0.3.4-geely-rc — 2026-10-08

**Upstream alignment:** not re-checked for this release. The anchor is unchanged from 0.3.3:
DiPlay `main` `5e58b4b` (2026-10-07), tag **v0.2.13**. The porting conclusions recorded there still
apply.

### Wired connection

- **The USB read timeout now works below API 26.** `UsbApiCompat.waitForUsbRequest` called the
  untimed `requestWait()` on API < 26, so `Iap2UsbSession.read(timeoutMillis)` ignored its deadline
  and `Iap2UsbMuxHost.begin()` could wait forever for a USBMUX version reply instead of failing
  after 60 seconds. The new `awaitUsbRequest` keeps the timed overload on API 26+ and, below it,
  cancels the request from a timer at the deadline — the same cross-thread unblocking `close()`
  already relied on. The NCM read keeps the shape it needs, where a timed-out request stays queued.
  Traced from the sibling `xikai6282/DiPlay-Geely-Android43` API18 patch. Not verified on the car.
- **The wired session no longer captures the phone's syslog.** The `com.apple.syslog_relay` opened
  after pairing shared the USBMUX pipe and its single reader thread with iAP2 control for two
  minutes. It could never see `0x52`, which precedes it, and its PHONE lines no longer reach the
  screen.

### Connection screen

- **Handshake steps and the USB bus are shown while connecting.** The connection screen carries a USB
  device list refreshed every 2 seconds and a timestamped, 24-line handshake log, both split off the
  existing `debugLog` stream; PHONE and TRACE lines still stay off the screen. The re-enumeration
  path logs more: the `0x52` failure now names vid/pid/class and the configuration list, and the
  controller records whether a CarPlay configuration was present and which reenum attempt it was. A
  manual retry button starts a new handshake and cancels a pending automatic one.

### Settings

- **A "restart application" button.** It tears the session down exactly as exit does and relaunches
  the launcher into a cleared task, without terminating the process — so the VPN permission is not
  requested again. It deliberately leaves the activity running until the relaunch, which would
  otherwise become a background activity launch that newer releases refuse. It lands on the
  launcher, and unapplied setting edits are discarded, as with exit.

---

## 0.3.3-geely-rc — 2026-10-07

**Upstream alignment:** checked against DiPlay `main` `5e58b4b` (2026-10-07), 19 non-merge commits
after the previous anchor `e2fd8ea`. The upstream tag is still **v0.2.13**. None of those 19 commits
were ported.

### Playback

- **Session logging and per-packet allocations leave the playback hot path.** A wired session no
  longer logs every carkit send, every USBMUX frame, or a successful iAP2 frame. Those lines were
  dropped by `DiagnosticRedactor` before they reached the file. Connection setup, failure, and
  disconnect logs stay. What remains is written on one `diplay-session-log` thread, with one
  `SimpleDateFormat` and a length counted in memory. The length and the writer lock are shared per
  path, so a writer still draining after the activity is recreated cannot rotate a log the new
  session just truncated.
- **Audio and video reuse their receive buffers.** Audio keeps one receive buffer, AAD, nonce,
  plaintext, and one ChaCha20-Poly1305 instance. Video reuses the 128-byte header and grows the body
  up to 1 MiB, then allocates that one frame and releases the buffer when the stream closes. The RTP
  buffer handed to the decoder and the bytes returned from `decryptFrame` are still new arrays: the
  decode thread holds them, and the next packet must not overwrite a frame still being decoded.
  Pairing still uses the one-shot `chachaSeal` / `chachaOpen` path. Platform ChaCha stays unported.

  Frame interval and GC were not measured on the car. The head unit was not connected over adb when
  this was built.

### Not ported from this grandfather range

- The settings navigation rail, its category grouping, translations, unused-string cleanup, and the
  follow-up fixes for scroll position, hotspot readiness, and cluster-consent dialogs. That UI is
  built around BYD vehicle, cluster, night-mode, and wheel-learning pages this fork does not carry,
  and this fork's settings activity is already a separate Geely layout.
- Experimental Smooth video, and the decoder offscreen-parking and surface-detach wait that exist
  to support it. It was measured on a BYD Tang with Qualcomm `c2.qti.avc.decoder` at 2560×1440 and
  60 fps, and it holds each frame for a display delay of 30–200 ms (about 140 ms at 30 fps to start).
  On this head unit the picture already leaves the app slower than it arrives, and the added delay
  would show up as later touch response. `releaseOutputBuffer(int, long)` is also API 21, below this
  fork's API 19 floor. The MediaTek decoder was not the one those runs describe.

---

## 0.3.2-geely-rc — 2026-10-07

**Upstream alignment:** checked against DiPlay `main` `e2fd8ea` (2026-10-07), 51 commits after
**v0.2.13**. Four of those commits were ported. The rest stay upstream.

### Head unit compatibility

- **Wireless CarPlay is gone from the UI on the E01, because this head unit cannot run it.**
  Wireless CarPlay carries its iAP2 leg over a Bluetooth RFCOMM socket. This head unit's Bluetooth
  stack offers third-party apps no such socket, and the omission is deliberate rather than
  unfinished — three independent removals agree: the AOSP-derived stack in `XCBTService` has the
  socket classes taken out (`createRfcommSocketToServiceRecord`, `listenUsingRfcommWithServiceRecord`
  and `fetchRemoteUuids` are all absent while `getBondedDevices` and `startDiscovery` remain), the
  ECARX facade declares `bt/spp/ISpp` and `ISppCallback` on the boot classpath without ever writing
  a `SppProxy`, and the GOC SDK's `CommandSppImp` returns a hardcoded `false` from every entry
  point. The vendor daemon `/system/bin/gocsdk` does implement SPP, but its 119-command vocabulary
  has no SDP query, no custom UUID and no raw RFCOMM, so it cannot reach the iPhone's iAP2 service
  either — and iOS opens no standard SPP to a non-MFi accessory. On the car, `reqSppConnect` to the
  paired iPhone returns `false` and the SPP service reports `isSppServiceReady() = 0`.

  **Bluetooth itself is unaffected.** Hands-free calls and music keep working, and a third-party
  app can still read the adapter state, the connected device and the local address. What is
  unavailable is a byte pipe over Bluetooth, and on this head unit the only thing that needs one is
  wireless CarPlay. The home screen, Connection setup and the in-session settings now offer USB
  alone, and `AirPlayPersistence.loadWirelessEnabled` is clamped to `false` so a value stored by an
  earlier build cannot start a connection the hardware has no way to complete. Other head units are
  untouched: the wireless switch and the hotspot controls still appear wherever the stack supports
  them. Wired USB CarPlay is unchanged and remains the supported path here.

### Audio

- **Navigation guidance reaches the right stream on the E01.** The fork defaulted the navigation
  stream to 14, BYD's driver-speaker stream. The E01 renumbers the vendor streams: there 14 is
  `STREAM_FM`, the FM tuner, and spoken guidance belongs on `STREAM_NAVI_TTS` at 10, which its own
  `AudioManager` declares. The default now follows the head unit. An installation that already
  stored a value keeps it — `getInt` only falls back to the default when the key is absent — so
  re-select the stream once in settings after updating.

- **Music yields while another app holds audio focus for a call.** A transient focus loss sets the
  media track gain to 0; ducking sets it to 0.2; a later gain restores 1. Phone and navigation
  tracks are left alone, and a permanent loss does not change the gain. The fork still requests
  focus with the API 8 call, because `AudioFocusRequest` is API 26. There is no settings switch;
  upstream's default is on. Adapted from `#339` (`5a05b2e` and the follow-up focus fixes). The
  BYD hang-up broadcast was not ported.

### Wireless

- **RFCOMM reports a missing stream and a stall instead of failing inside the platform getter.**
  The duplex stream now owns the connected socket, so a failed getter cannot leave a second owner.
  Ported from `5fc7a7a`. Not yet verified on the car.

### USB

- **A four-byte USBMUX trailer is also accepted before a captured protocol-1 diagnostic frame,**
  in addition to the TCP replies already handled. A malformed diagnostic is still a protocol
  error. Ported from `5fc7a7a`.

### Diagnostics

- **Video receive logs now include decrypt time** (`decryptAvgUs`, `decryptMaxUs`, `decryptMBps`).
  Ported from `5b2fb6e`. The platform ChaCha20-Poly1305 path was not ported: that cipher is API 28,
  so on this head unit it would only fall back to the BouncyCastle implementation already in use.

### Not ported from this grandfather range

- Scheduled day/night mode, because `CarPlayNightMode` is not on this branch yet.
- Assignable Siri wheel key, because it depends on the wheel-key learning UI this fork does not carry.
- Custom image picker, because that settings surface is not on this branch.
- Software video windows, because they depend on view-area layout this fork does not carry.
- USB read-queue size fallback, because it targets `UsbRequest.queue(ByteBuffer)` (API 26) and this
  fork's supported range stops at Android 7.
- Traditional Chinese, website pages, and release-site commits.

---

## 0.3.1-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.13** (483 commits ahead of the v0.2.7 base).
That release added 19 commits over the previously screened point, of which exactly one is portable
here; the rest is Traditional Chinese translations and upstream-only documentation.

### Wireless

- **Fix wireless CarPlay failing on head units with no NSD service.** The Bonjour advertiser looked
  Android's NSD service up eagerly while constructing, even though this fork advertises over
  interface-bound mDNS (`useInterfaceMdns = true`) and never touches NSD. Where the service is
  absent the lookup returned null and the whole wireless stack failed before it started. The lookup
  is now deferred and nullable. Ported from upstream `32550b2`. Not yet verified on the car — the
  change can only remove a failure, it adds no behaviour to observe.

### Build

- **`gradlew` regains its executable bit.** Both workflows invoke `./gradlew`, which had been
  failing with `Permission denied` on every push; local runs used `sh gradlew`, so it stayed hidden
  and `Android checks` was red throughout.
- **Releases are now automatic.** Pushing to `main` derives the tag from `versionName` and publishes
  the APK with `SHA256SUMS.txt`; a version that already has a Release is skipped. Publishing is
  gated on the tag matching `versionName`, the release notes existing, the source tree being free of
  credentials, and the APK carrying a valid v1+v2 signature and the accessory identity.

---

## 0.3.0-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.12 + unreleased** (464 commits ahead of base).

### Diagnostics

- **New: wireless startup diagnostics.** When a wireless connection stalls, the report now says which
  step it stalled at, alongside interface state (up/down, multicast, usable address families),
  Bonjour discovery counters and kernel receive/UDP counters. Observation only — it does not change
  connection deadlines, address selection or retry behaviour. The watchdog that would have armed a
  timeout after the start-session request is deliberately **not** included, since that changes
  behaviour rather than observing it.

### Documentation

- README, README.zh-CN and this changelog now describe **this fork only**. Upstream's BYD feature
  lists, its website links and its preview copy are gone; the GPL-3.0 / AGPL-3.0 attribution and the
  accessory-identity disclosure are retained.
- The compatibility, install, privacy, connection-setup and testing docs no longer carry BYD-only
  sections.

---

## 0.2.9.1-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.7 + unreleased**.

The fork's first published release. Earlier `-geely-rc` tags were withdrawn before release, so
everything the fork added up to that point is listed here.

### Steering wheel — verified on the car

- Fix the steering wheel doing nothing on a Geely head unit. The wheel goes through the car's own
  input service, which re-emits each press as a standard `ACTION_MEDIA_BUTTON`, but DiPlay's media
  session declared `flags=0` and had no media-button receiver, so the framework gave every press to
  whichever app held the wheel before. Declaring `FLAG_HANDLES_MEDIA_BUTTONS` and setting a
  media-button receiver fixed it. Confirmed on the car: `dumpsys media_session` went from
  `flags=0 / mediaButtonReceiver=null` to `flags=3 / PendingIntent{...}`, and next/previous track now
  reach CarPlay. This wheel has no play/pause key — that position is the head unit's screen-mirroring
  button, which never enters the Android key pipeline.
- The generic accessibility key filter and the ECARX vendor key codes remain as fallbacks for other
  head units. Neither is needed here; GKUI 19 on this car exposes no accessibility settings entry.
- Complete the missing Arabic, Russian and Spanish translations for the wheel-key strings.

### Wireless

- Switch the car's own hotspot on instead of leaving the driver to do it in the car settings. The
  head unit's existing hotspot configuration is kept, and Wi-Fi station mode is released only on a
  retry, since that disconnects the car from whatever network it is on. Failures are now reported by
  kind: firmware withholding the permission, the car refusing the request, or the hotspot not
  reporting itself enabled in time.

### USB and wired

- Fix the USB attach filter missing the Apple vendor id, so plugging in an iPhone cold did not launch
  DiPlay.
- Tolerate the 4-byte padding after a USBMUX control reply, which used to abort the connection with a
  protocol error.
- Add USB permission auto-confirm (an accessibility service) to remove the system's authorisation
  prompt. It answers only the system dialog, never on behalf of another app.
- Fix wired connections being rejected whenever manual hotspot mode was configured: the runtime
  config demanded a hotspot SSID even when the connection was wired. The check now applies to
  wireless only.

### Audio and touch

- Raise the UDP receive buffer to 512 KB and add a stall timeline (largest inter-arrival gap,
  sequence gaps).
- Disable Nagle's algorithm on the touch event channel so gestures no longer arrive in batches.

### Diagnostics

- Export reports on head units with no file picker, with three levels of fallback and an on-screen
  view that can be copied by long-press.

### Head unit compatibility

- Stop the SOME/IP HUD bridge retrying a service a Geely head unit can never reach: it now checks its
  gateway package is installed before binding. The retry used to run every 300 ms.
- Fix the Android 5 settings page, where switches rendered their on/off captions incorrectly.
- Recognise short-form component names when reading whether an accessibility service is enabled.

---

## Base — upstream DiPlay v0.2.7

Everything not listed above is upstream's work, carried over unchanged. That includes features
specific to BYD head units (DiLink cluster projection, BYD HUD navigation, CAN battery reporting),
which are **out of scope for this fork** rather than pending — this head unit has no counterpart
hardware. The code stays in the tree so upstream syncs remain cheap.
