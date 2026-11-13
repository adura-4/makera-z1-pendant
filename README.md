# Z1 Pendant

Android "digital pendant" for the Makera Z1 Pro on **stock firmware** over Wi-Fi. Nothing on the
machine is modified. APK: `z1-pendant-0.1.0.apk` (release build, debug-signed, minSdk 31).

## Layout
- `python/z1proto.py`: wire protocol (frames, CRC, status parser, discovery parser), with self-test.
- `python/z1client.py`: PC test client: `discover`, `probe` (read-only), `second` (two TCP clients),
  `send`, `jog`. Anything that moves needs `--motion`.
- `python/z1sim.py`: machine simulator (discovery broadcast, TCP 2222, G0/G1/G90/G91/G10 L20/$H/$X,
  M220, hold/resume/halt, 32-block queue, live soft limits X −207..0, Y −206..0, Z −102..0).
- `android/`: Kotlin + Jetpack Compose app. Build: `gradlew.bat assembleRelease`.
  - `link/MachineLink.kt`: the transport interface (+ `Dialect` for backend-specific G-code).
    `link/makera/*` is the stock-firmware backend. A LinuxCNC backend implements the same interface.
  - `jog/JogEngine.kt`: click batching and the lead cap (below).
  - `hotkeys/*`: model, JSON import/export, runner with guards.

## Protocol (confirmed: Makera app decompile + community firmware `MakeraFrame.h`)
`86 68 | len u16 BE | type | payload | CRC u16 BE | 55 AA`, len = 1 + payload + 2,
CRC-16/XMODEM (poly 0x1021, **init 0**) over len + type + payload. Firmware test vector:
`86 68 00 03 71 3b e5 55 aa`.
- To machine: `0xA1` one real-time byte (`?` status, `!` hold, `~` resume, `0x18` halt, `0x19` stop,
  `0x1A` keep-alive); `0xA2` one command line (no newline needed).
- From machine: `0x81` status `<Idle|MPos:x,y,z,a,b|WPos:…|F:feed,set,override|S:…|…>`,
  `0x90` text (`ok`, errors, alarms), `0x71` firmware version (periodic), `0x82` diagnose.
- Discovery: the ESP32 broadcasts `name,ip,port,busy,…` to **UDP 3333** (port read from the app's
  `discover()` at 0x780e80; format string `%s,%s,%d,%d,%s` in the ESP32 dump).
- Stock firmware has no `$J` / continuous jog (only a `test jog` shell command), so the pendant jogs
  with short `G91 G1` moves and restores `G90` when the wheel stops.

## Jog engine
Every 40 ms, the clicks collected become one `G91 G1` move. Feed = hand speed (clicks in the last 200 ms
× step) plus a correction that keeps about 120 ms of motion queued:
`feed = vHand + (lead − vHand·0.12 s) / 0.3 s`, clamped to 5 mm/min … axis max.
Why the buffer: Smoothie holds a queue that was empty for `queue_delay_time_ms` (100 ms) before running
it. If the queue runs dry mid-turn, the machine stops and restarts 100 ms late (this was the "laggy,
not smooth" feel in the first finger test). The **lead** (commanded but not yet travelled, dead-reckoned
from the last status) plus waiting clicks is capped at
`max(min(5 clicks, 5 mm), v²/2a + v·tick + vHand·(0.12 + 0.1) + 1 click)` (steps 0.005 · 0.01 · 0.1 · 1 · 5 mm;
the 5 mm floor means one click at the 5 mm step). Clicks beyond that are dropped, with at
most one thud per 150 ms. When input stops or the link drops, the axis stops within that distance.
Simulator (with the 100 ms start delay modelled): slow and moderate turns start once and never run dry,
with no dropped clicks. Spinning faster than 50 mm/s pins F3000 and drops the excess.

## Haptics
Vibration primitives (Pixel 10 and S24 Ultra support them): TICK per detent, CLICK every 10th,
THUD when clicks are dropped or blocked, double click at a soft limit. Strength is in Settings.

## Hotkey grid
Collapsed: the dial plus the first 8 hotkeys (those with "Show in hotkey grid" on). Tap **Hotkeys ▾** to
expand: the dial hides and every hotkey shows. **Edit** (expanded only) shows all hotkeys, including
button-only ones dimmed, adds **+ New**, and lets you drag tiles anywhere (others slide live). It turns
into **Save**, which commits the order. Hold a tile **2 s** to edit it (a ring fills). A quick tap runs it.
A hold released early does nothing.

## Buttons
Volume up/down = ±1 click (hold to repeat). Any key event can be bound to a hotkey, short or long
press (long = 0.5 s), optionally per device: volume keys, Bluetooth remotes and gamepads, USB
keyboards, a Pico acting as a USB HID keyboard. Bindings only act on the main screen.
Unbound gamepad buttons are swallowed there (B would otherwise act as Back).

## Test hooks
- `am start -n com.z1pendant/.MainActivity --es host <ip> [--ei port 2222] [--es name …]` connects.
- `… --ei testclicks <±n> --ei testms <ms> [--ei teststep 0..4]` injects a click burst.
- Dell test rig (`~/z1pendant`): `simctl.sh start|stop`, `wake.sh`, `tap.sh "<text>"`, `wheel.sh`.
  The Dell's ufw blocks inbound 2222, so the Pixel reaches the sim via `adb reverse tcp:2222 tcp:2222`
  and `--es host 127.0.0.1`. Discovery over Wi-Fi works (the sim broadcasts on 192.168.1.255).

## Confirmed on the real Z1 (2026-11-13, fw `version = 1.1.3.0.1.13`, IP 192.168.1.48)
- Discovery broadcast: `Makera_Z1P_024816,192.168.1.48,2222,0,Idle`. The 5th field is the machine state,
  and "busy" stayed 0 with Makera Studio connected.
- Status: `<Idle|MPos:x,y,z,a,b|WPos:…|F:feed,set,ovr|S:…|T:tool,tlo,…|L:…|C:…|E:…|OTA:…>`. No `H:` field
  and no periodic 0x71 version packets.
- G-code lines get `ok` (0x90). Shell commands (`version`) reply with text only, so the app waits for an
  `ok` only on G-code / `$` lines. Errors can arrive split across packets (`error:` then the message).
- Several TCP clients at once: Makera Studio, the app and two test clients each got only their own replies.
- **Upper soft limit = the homed position, MPos −1.000** (X+0.1 from −1.000 → "Soft Endstop X was exceeded",
  Alarm; cleared from Studio). The machine homes at power-on. The app caps X/Y/Z at −1 (Y/Z assumed) and
  clamps unless the status says "not homed".
- First real move: `G91G1X-0.1F300` + `G90` → both `ok`, MPos X −1.000 → −1.100.
- App dial on the Pixel 10: 5 clicks at 0.01 → 5× `G91G1X-0.01F30` + `G90`, all acked, MPos X −1.100 → −1.150 (too small to see or hear).
- App dial, 10 clicks at 1 mm: 10× `G91G1X-1.0F1500` + `G90`, MPos X −1.150 → −11.150; **confirmed by eye: the axis moved.**
- Status RTT over Wi-Fi: PC median ~30 ms. On the Pixel, Wi-Fi power save gave 18–300 ms until the app held
  `WIFI_MODE_FULL_LOW_LATENCY` while visible (now mostly 17–42 ms).

- `diagnose` (sent as an 0xA2 line; the `*` real-time byte gets no reply) returns a 0x82 frame
  `{S:…|L:…|V:…|F:…|G:light,…|T:…|C:…|E:…|P:…|I:…|RSSI:…}` with no `ok`. **G[0] = light** (M822 → 0, M821 → 1,
  confirmed). The app reads it every 3 s for the Light toggle tile (white = on, dimmed = off).

## Still unverified
1. Vacuum M851/M852.
2. Y/Z upper soft limits (assumed −1 like X).
3. Real acceleration feel of the dial (the sim moves at constant speed; the machine accelerates at 150 mm/s²).
