# Z1 Pendant: developer notes

How the app talks to the machine, how jogging works, and what has been confirmed on a real Z1 Pro.
For installing and using the app, see the [README](../README.md).

## Source layout
- `android/`: the Gradle project (Kotlin 2.2, Compose Multiplatform 1.9, minSdk 31). Build the
  Android app with `gradlew assembleRelease`.
  - `shared/`: Kotlin Multiplatform module with nearly all the code, for Android and iOS
    (`iosArm64`, `iosSimulatorArm64`; static framework `Shared`). In `src/commonMain/kotlin/com/z1pendant/`:
    - `link/MachineLink.kt`: the transport interface plus `Dialect` (backend-specific G-code).
      `link/makera/*` is the stock-firmware backend (TCP frames, discovery; sockets are ktor-network).
      Another controller (e.g. LinuxCNC) would implement the same interface.
    - `jog/JogEngine.kt`: turns dial/pad/volume clicks into short relative moves (below).
    - `hotkeys/*`: hotkey model, JSON import/export (kotlinx-serialization, same file format as before),
      runner with guards (ask first, Idle only).
    - `ui/*`: all the Compose UI: main screen, handwheel, jog pad, hotkey grid and editor, Move box,
      settings, console. `AppViewModel.kt`: state and actions.
    - `platform/Platform.kt`: what each phone supplies (preferences, hotkeys.json, haptics, clock,
      back button, hotkey file picker); `util/Fmt.kt`: number formatting identical to Java's.
  - `shared/src/androidMain`: SharedPreferences "z1pendant", filesDir/hotkeys.json, vibrator, file pickers.
  - `shared/src/iosMain`: NSUserDefaults, Documents/hotkeys.json, Taptic feedback, and
    `MainViewController()` for the iOS app. Not compiled on Windows; the Xcode project comes later on a Mac.
  - `shared/src/androidUnitTest`: formatter vs Java, hotkey JSON vs org.json, frames, and the link
    against `z1sim.py` (`gradlew :shared:testDebugUnitTest`).
  - `app/`: the Android shell: `MainActivity` (key events, Wi-Fi low-latency lock, keep-screen-on,
    adb test hooks), manifest, icon.
- `python/z1proto.py`: wire protocol (frames, CRC, status and discovery parsers) with a self-test.
- `python/z1client.py`: PC test client: `discover`, `probe` (read-only), `second` (two TCP clients
  at once), `send`, `jog`. Anything that can move the machine needs `--motion`.
- `python/z1sim.py`: machine simulator: discovery broadcast, TCP 2222, G0/G1/G90/G91/G10 L20/G53/$H/$X,
  M220, M821/M822, M851/M852, `diagnose`, hold/resume/halt, a 32-block queue with the firmware's 100 ms
  start delay, and soft limits X −207..−1, Y −206..−1, Z −102..−1 that alarm like the real machine.

## Protocol (Makera app decompile + community firmware `MakeraFrame.h`)
`86 68 | len u16 BE | type | payload | CRC u16 BE | 55 AA`, len = 1 + payload + 2,
CRC-16/XMODEM (poly 0x1021, **init 0**) over len + type + payload. Test vector: `86 68 00 03 71 3b e5 55 aa`.
- To the machine: `0xA1` one real-time byte (`?` status, `!` hold, `~` resume, `0x18` halt, `0x19` stop,
  `0x1A` keep-alive); `0xA2` one command line (no newline).
- From the machine: `0x81` status `<Idle|MPos:x,y,z,a,b|WPos:…|F:feed,set,override|S:…|…>`,
  `0x90` text (`ok`, errors, alarms), `0x82` diagnose reply, `0x71` firmware version.
- Discovery: the machine's ESP32 broadcasts `name,ip,port,busy,state` to **UDP 3333**.
- Stock firmware has no `$J` / continuous jog, so the pendant jogs with short `G91 G1` moves and
  restores `G90` when the input stops.

## Jog engine
Every 40 ms the clicks collected become one `G91 G1` move. Feed = hand speed (clicks in the last
200 ms × step) plus a correction that keeps about 120 ms of motion queued:
`feed = vHand + (lead − vHand·0.12 s) / 0.3 s`, clamped to 5 mm/min … axis max.

Why the buffer: Smoothie holds a queue that was empty for `queue_delay_time_ms` (100 ms) before running
it. If the queue runs dry mid-turn, the machine stops and restarts 100 ms late, which feels laggy.

The **lead** (commanded but not yet travelled, dead-reckoned from the last status) plus waiting clicks
is capped at `max(min(5 clicks, 5 mm), v²/2a + v·tick + vHand·(0.12 + 0.1) + 1 click)`. Clicks beyond
that are dropped (one thud vibration per 150 ms at most), so when input stops or the link drops, the
axis stops within that distance. Moves are clamped to the soft limits while the machine is homed.

Jog pad: a tap is one click; a held button repeats after 0.4 s at `max(120 ms, step / axis max speed)`,
and letting go drops any clicks not yet sent.

## Haptics
VibrationEffect composition primitives: TICK per detent, CLICK every 10th, THUD when clicks are dropped
or blocked, a double click at a soft limit. Strength is in Settings.
On Android 13+ they play as `USAGE_PHYSICAL_EMULATION` (vibrations that stand in for a hardware part,
here the handwheel's detents). Without attributes Android files short primitives as touch feedback, and
the phone's **Touch feedback** switch then silences them; many people turn that off to quiet the
keyboard. Android's master **Use vibration & haptics** switch still applies, and the strength slider
(0 = off) is the app's own off switch. Android 12 has no such usage and keeps the default (touch).
iOS (not tested yet): light / medium / heavy impacts for tick / every 10th / thud, two rigid knocks
for a soft limit, a selection click for button presses.

## Machine outputs
`diagnose` (sent as an `0xA2` line) returns an `0x82` frame
`{S:…|L:…|V:…|F:…|G:light,beep,extendin,extendout,extendout_pwm|T:…|C:…|E:…|P:…|I:…|RSSI:…}` with no `ok`.
G[0] = light (M821 on / M822 off), G[3] = vacuum / chip-clear output (`switch.extendout`, M851 on /
M852 off). The app reads it every 3 s (not while jogging) for the Light and Vacuum toggle tiles.

## Zero / undo
`zero` sends `G10 L20 P0 <axis>0`. Pressed again while the axis still reads 0.000 and hasn't moved, it
sends `G10 L20 P0 <axis><previous value>` instead. The Zero hotkeys (X/Y/Z/A, XY, XYZ, selected) share it.

## Typed moves
The Move box sends `G90 G0 <axis><value>` (Go to) or `G91 G0 <axis><value>` + `G90` (Move by), and
refuses targets past the soft limits before sending anything.

## Test hooks
Debug builds only (`gradlew assembleDebug`); a release build ignores these extras, so other apps
can't use them to move the machine.
- `adb shell am start -n com.z1pendant/.MainActivity --es host <ip> [--ei port 2222] [--es name …]` connects.
- `… --ei testclicks <±n> --ei testms <ms> [--ei teststep <index>]` injects a burst of dial clicks.
- Simulator: `python3 python/z1sim.py`, then connect the app to that computer's IP (or
  `adb reverse tcp:2222 tcp:2222` and `--es host 127.0.0.1`).

## Confirmed on a real Z1 Pro (stock firmware 1.1.3.0.1.13)
- Discovery broadcast: `Makera_Z1P_xxxxxx,<ip>,2222,0,Idle`. The 5th field is the machine state; "busy"
  stayed 0 with Makera Studio connected.
- Status has no `H:` field and the firmware sends no periodic `0x71` version packets.
- G-code and `$` lines get `ok`; shell commands (`version`) reply with text only, so the app waits for
  an `ok` only on G-code / `$` lines. Errors can arrive split across packets.
- Several TCP clients at once work (Makera Studio, the app, test clients), each getting its own replies.
- **Upper soft limit = the homed position, machine −1.000.** A move past it alarms ("Soft Endstop X was
  exceeded"); that's why the app's "Z up" goes to `G53 G0 Z-2`, not Z0. The machine homes at power-on.
- Dial jogs at 0.01 and 1 mm per click, M821/M822 light, M851/M852 vacuum: all confirmed.
- Status round trip over Wi-Fi: ~30 ms median from a PC. On a Pixel, Wi-Fi power save gave 18–300 ms
  until the app held `WIFI_MODE_FULL_LOW_LATENCY` while visible (then mostly 17–42 ms).

## Still unverified
- Y/Z upper soft limits (assumed −1 like X).
