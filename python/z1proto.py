"""Makera Z1 wire protocol (stock firmware, via the ESP32's TCP/USB bridge).

Sources (see the project CLAUDE.md):
  * Makera Android app decompile (blutter): cnc_protocol.dart buildPacket / Crc16Ccitt.compute,
    wifi_connection.dart discover().
  * Carvera Community Firmware libs/PublicData.h, libs/MakeraFrame.h, SerialConsole.cpp.

Frame:  86 68 | len u16 BE | type u8 | payload | crc u16 BE | 55 AA
  len = 1 (type) + len(payload) + 2 (crc)
  crc = CRC-16/XMODEM (poly 0x1021, init 0x0000, no reflection) over len(2) + type + payload.
  Firmware test vector: type 0x71, no payload -> 86 68 00 03 71 3b e5 55 aa.

One 0xA2 frame = one command line; no newline needed (the firmware dispatches the
payload as one console line).

Discovery: the ESP32 broadcasts "name,ip,port,busy,..." on UDP 3333 (the app strips any <...>).
"""
from __future__ import annotations

import re
import struct
from dataclasses import dataclass, field

HEADER = 0x8668
FOOTER = 0x55AA
TCP_PORT = 2222
UDP_DISCOVERY_PORT = 3333

# Packet types (PublicData.h)
CTRL_SINGLE = 0xA1   # one real-time byte: ? status, * diagnose, ! hold, ~ resume, 0x18 halt, 0x19 stop
CTRL_MULTI = 0xA2    # one command line
FILE_START = 0xB0
FIRM_VER = 0x71
STATUS_RES = 0x81
DIAG_RES = 0x82
LOAD_INFO = 0x83
LOAD_FINISH = 0x84
LOAD_ERROR = 0x85
NORMAL_INFO = 0x90

TYPE_NAMES = {
    CTRL_SINGLE: "CTRL_SINGLE", CTRL_MULTI: "CTRL_MULTI", FIRM_VER: "FIRM_VER",
    STATUS_RES: "STATUS", DIAG_RES: "DIAG", LOAD_INFO: "LOAD_INFO",
    LOAD_FINISH: "LOAD_FINISH", LOAD_ERROR: "LOAD_ERROR", NORMAL_INFO: "INFO",
}

RT_STATUS = b"?"
RT_DIAG = b"*"
RT_HOLD = b"!"
RT_RESUME = b"~"
RT_HALT = b"\x18"      # Ctrl-X: halt (machine enters Alarm, needs $X)
RT_STOP = b"\x19"      # Ctrl-Y: stop request
RT_KEEPALIVE = b"\x1a"  # Ctrl-Z


def _crc_table() -> list[int]:
    t = []
    for i in range(256):
        c = i << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) if c & 0x8000 else (c << 1)
        t.append(c & 0xFFFF)
    return t


_TABLE = _crc_table()


def crc16(data: bytes) -> int:
    c = 0
    for b in data:
        c = ((c << 8) & 0xFFFF) ^ _TABLE[((c >> 8) ^ b) & 0xFF]
    return c


def build(ptype: int, payload: bytes = b"") -> bytes:
    body = struct.pack(">HB", len(payload) + 3, ptype) + payload
    return struct.pack(">H", HEADER) + body + struct.pack(">HH", crc16(body), FOOTER)


def cmd(line: str) -> bytes:
    return build(CTRL_MULTI, line.encode("ascii"))


def rt(ch: bytes) -> bytes:
    return build(CTRL_SINGLE, ch)


class Decoder:
    """Byte-stream frame decoder. feed() returns a list of (type, payload)."""

    def __init__(self):
        self.buf = bytearray()
        self.bad_crc = 0

    def feed(self, data: bytes) -> list[tuple[int, bytes]]:
        self.buf += data
        out = []
        while True:
            i = self.buf.find(b"\x86\x68")
            if i < 0:
                del self.buf[:-1]
                return out
            del self.buf[:i]
            if len(self.buf) < 4:
                return out
            ln = struct.unpack(">H", self.buf[2:4])[0]
            if ln < 3 or ln > 8199:          # download packets: 8192 data + 7
                del self.buf[:2]
                continue
            total = 2 + 2 + ln + 2
            if len(self.buf) < total:
                return out
            frame = bytes(self.buf[:total])
            body = frame[2:4 + ln - 2]
            crc = struct.unpack(">H", frame[4 + ln - 2:4 + ln])[0]
            foot = struct.unpack(">H", frame[4 + ln:4 + ln + 2])[0]
            if foot != FOOTER or crc != crc16(body):
                self.bad_crc += 1
                del self.buf[:2]
                continue
            out.append((body[2], body[3:]))
            del self.buf[:total]


@dataclass
class Status:
    state: str = "?"
    mpos: list[float] = field(default_factory=list)
    wpos: list[float] = field(default_factory=list)
    fields: dict[str, list[str]] = field(default_factory=dict)

    @property
    def feed_override(self) -> float | None:
        f = self.fields.get("F")
        return float(f[2]) if f and len(f) >= 3 else None


_STATUS_RE = re.compile(r"<([^>]*)>")


def parse_status(text: str) -> Status | None:
    m = _STATUS_RE.search(text)
    if not m:
        return None
    parts = m.group(1).split("|")
    st = Status(state=parts[0])
    for p in parts[1:]:
        if ":" not in p:
            continue
        k, v = p.split(":", 1)
        vals = [x.strip() for x in v.split(",")]
        st.fields[k] = vals
        if k in ("MPos", "WPos"):
            try:
                nums = [float(x) for x in vals]
            except ValueError:
                continue
            if k == "MPos":
                st.mpos = nums
            else:
                st.wpos = nums
    return st


def parse_discovery(text: str) -> dict | None:
    t = text.strip().strip("<>").strip("\x00")
    parts = [x.strip() for x in t.split(",")]
    if len(parts) < 2:
        return None
    try:
        port = int(parts[2]) if len(parts) > 2 else TCP_PORT
    except ValueError:
        port = TCP_PORT
    return {"name": parts[0], "ip": parts[1], "port": port,
            "busy": len(parts) > 3 and parts[3] == "1", "extra": parts[4:]}


def _selftest():
    assert build(FIRM_VER) == bytes.fromhex("8668000371" "3be5" "55aa"), build(FIRM_VER).hex()
    d = Decoder()
    pkts = d.feed(b"\x00junk" + cmd("G91G0X0.1") + rt(b"?")[:5])
    assert pkts == [(CTRL_MULTI, b"G91G0X0.1")], pkts
    pkts = d.feed(rt(b"?")[5:])
    assert pkts == [(CTRL_SINGLE, b"?")], pkts
    s = parse_status("<Idle|MPos:1.0000,2.0000,3.0000,0.0000,0.0000|WPos:0.5000,2.0000,3.0000,0.0000,0.0000|F:0.0,3000.0,100.0>")
    assert s.state == "Idle" and s.mpos[0] == 1.0 and s.wpos[0] == 0.5 and s.feed_override == 100.0
    assert parse_discovery("Makera_Z1P_024816,192.168.1.50,2222,0,1.0")["ip"] == "192.168.1.50"
    print("selftest ok")


if __name__ == "__main__":
    _selftest()
