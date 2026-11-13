"""Makera Z1 test client (stock firmware over Wi-Fi, TCP 2222).

  python z1client.py discover [--secs 6]
  python z1client.py probe   <ip> [--secs 10] [--hz 5]       read-only: status polls + log every packet
  python z1client.py second  <ip> [--secs 8]                  second TCP client test (run while Makera Studio is connected)
  python z1client.py send    <ip> "<line>" [--motion]         one command line, logs replies for 3 s
  python z1client.py jog     <ip> <axis> <mm> [--feed 300] --motion   one small relative move, then G90

Anything that can move the machine needs --motion. Every received packet is printed with a
timestamp and also appended to z1client.log next to this script.
"""
from __future__ import annotations

import argparse
import os
import socket
import statistics
import sys
import threading
import time

import z1proto as P

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "z1client.log")
MOTION_WORDS = ("G0", "G1", "G2", "G3", "G28", "G38", "$H", "M3", "M4", "M6", "G53")


def log(msg: str):
    line = f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def describe(ptype: int, payload: bytes) -> str:
    name = P.TYPE_NAMES.get(ptype, f"0x{ptype:02X}")
    try:
        text = payload.decode("utf-8")
        return f"[{name}] {text!r}"
    except UnicodeDecodeError:
        return f"[{name}] hex {payload.hex()}"


class Conn:
    def __init__(self, ip: str, port: int = P.TCP_PORT, tag: str = ""):
        self.tag = tag
        self.sock = socket.create_connection((ip, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(0.2)
        self.dec = P.Decoder()
        self.statuses: list[P.Status] = []
        self.rtts: list[float] = []
        self._q_sent: float | None = None
        self.count = 0
        self.alive = True
        self.quiet_status = False
        self._rx = threading.Thread(target=self._rx_loop, daemon=True)
        self._rx.start()

    def _rx_loop(self):
        while self.alive:
            try:
                data = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError as e:
                if self.alive:
                    log(f"{self.tag}rx error: {e}")
                self.alive = False
                return
            if not data:
                log(f"{self.tag}connection closed by peer")
                self.alive = False
                return
            for ptype, payload in self.dec.feed(data):
                self.count += 1
                if ptype == P.STATUS_RES:
                    if self._q_sent is not None:
                        self.rtts.append((time.perf_counter() - self._q_sent) * 1000)
                        self._q_sent = None
                    st = P.parse_status(payload.decode("utf-8", "replace"))
                    if st:
                        self.statuses.append(st)
                    if self.quiet_status:
                        continue
                log(f"{self.tag}<- {describe(ptype, payload)}")

    def send(self, frame: bytes, note: str):
        log(f"{self.tag}-> {note}  ({frame.hex(' ')})")
        self.sock.sendall(frame)

    def query(self):
        self._q_sent = time.perf_counter()
        self.sock.sendall(P.rt(P.RT_STATUS))

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass


def cmd_discover(a):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", P.UDP_DISCOVERY_PORT))
    s.settimeout(0.5)
    log(f"listening on UDP {P.UDP_DISCOVERY_PORT} for {a.secs}s")
    seen = {}
    end = time.time() + a.secs
    while time.time() < end:
        try:
            data, addr = s.recvfrom(1024)
        except socket.timeout:
            continue
        text = data.decode("utf-8", "replace")
        info = P.parse_discovery(text)
        if addr not in seen:
            log(f"from {addr[0]}:{addr[1]}  raw={text!r}  parsed={info}")
        seen[addr] = seen.get(addr, 0) + 1
    log(f"done; {sum(seen.values())} broadcasts from {len(seen)} source(s)")


def poll_loop(c: Conn, secs: float, hz: float):
    end = time.time() + secs
    while time.time() < end and c.alive:
        c.query()
        time.sleep(1.0 / hz)


def report(c: Conn):
    if c.rtts:
        r = sorted(c.rtts)
        log(f"{c.tag}status replies: {len(r)}; RTT ms min {r[0]:.1f} median {statistics.median(r):.1f} "
            f"p95 {r[int(len(r) * 0.95) - 1]:.1f} max {r[-1]:.1f}")
    else:
        log(f"{c.tag}no status replies")
    if c.statuses:
        last = c.statuses[-1]
        log(f"{c.tag}last status: state={last.state} MPos={last.mpos} WPos={last.wpos} "
            f"fields={ {k: v for k, v in last.fields.items() if k not in ('MPos', 'WPos')} }")
    log(f"{c.tag}packets received: {c.count}, bad CRC/frames: {c.dec.bad_crc}")


def cmd_probe(a):
    c = Conn(a.ip)
    log(f"connected to {a.ip}:{P.TCP_PORT}; listening 2 s before polling")
    time.sleep(2)
    c.quiet_status = not a.verbose
    c.send(P.rt(P.RT_STATUS), "? (first status, printed)")
    c.quiet_status = False
    time.sleep(0.5)
    c.quiet_status = not a.verbose
    poll_loop(c, a.secs, a.hz)
    time.sleep(0.5)
    report(c)
    c.close()


def cmd_second(a):
    c1 = Conn(a.ip, tag="[A] ")
    log("[A] connected")
    try:
        c2 = Conn(a.ip, tag="[B] ")
        log("[B] connected (TCP accept succeeded)")
    except OSError as e:
        log(f"[B] connect failed: {e}")
        c2 = None
    for c in (c1, c2):
        if c:
            c.quiet_status = True
    threads = [threading.Thread(target=poll_loop, args=(c, a.secs, 4)) for c in (c1, c2) if c]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    time.sleep(0.5)
    for c in (c1, c2):
        if c:
            report(c)
            c.close()


def is_motion(line: str) -> bool:
    u = line.upper().replace(" ", "")
    return any(w in u for w in MOTION_WORDS)


def cmd_send(a):
    if is_motion(a.line) and not a.motion:
        sys.exit(f"'{a.line}' can move the machine; add --motion once you're sure it's safe")
    c = Conn(a.ip)
    time.sleep(0.5)
    c.send(P.cmd(a.line), repr(a.line))
    t_end = time.time() + 3
    while time.time() < t_end:
        c.query()
        c.quiet_status = True
        time.sleep(0.25)
    report(c)
    c.close()


def cmd_jog(a):
    if not a.motion:
        sys.exit("jog moves the machine; add --motion once you're sure it's safe")
    if abs(a.mm) > 1.0:
        sys.exit("test jog limited to 1 mm")
    axis = a.axis.upper()
    if axis not in "XYZA":
        sys.exit("axis must be X/Y/Z/A")
    c = Conn(a.ip)
    c.quiet_status = True
    c.query()
    time.sleep(0.4)
    before = c.statuses[-1] if c.statuses else None
    log(f"before: {before.state if before else '?'} MPos={before.mpos if before else '?'}")
    if before and before.state != "Idle":
        c.close()
        sys.exit(f"machine not Idle ({before.state}); not jogging")
    c.send(P.cmd(f"G91G1{axis}{a.mm:.4f}F{a.feed:.0f}"), "jog")
    c.send(P.cmd("G90"), "restore absolute mode")
    t_end = time.time() + 4
    while time.time() < t_end:
        c.query()
        time.sleep(0.1)
    after = c.statuses[-1] if c.statuses else None
    log(f"after: {after.state if after else '?'} MPos={after.mpos if after else '?'}")
    report(c)
    c.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("discover"); p.add_argument("--secs", type=float, default=6); p.set_defaults(fn=cmd_discover)
    p = sub.add_parser("probe"); p.add_argument("ip"); p.add_argument("--secs", type=float, default=10)
    p.add_argument("--hz", type=float, default=5); p.add_argument("-v", "--verbose", action="store_true"); p.set_defaults(fn=cmd_probe)
    p = sub.add_parser("second"); p.add_argument("ip"); p.add_argument("--secs", type=float, default=8); p.set_defaults(fn=cmd_second)
    p = sub.add_parser("send"); p.add_argument("ip"); p.add_argument("line"); p.add_argument("--motion", action="store_true"); p.set_defaults(fn=cmd_send)
    p = sub.add_parser("jog"); p.add_argument("ip"); p.add_argument("axis"); p.add_argument("mm", type=float)
    p.add_argument("--feed", type=float, default=300); p.add_argument("--motion", action="store_true"); p.set_defaults(fn=cmd_jog)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
