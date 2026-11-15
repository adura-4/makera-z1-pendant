"""Makera Z1 simulator: speaks the stock wire protocol so the pendant app can be tested with the
machine off. Not a faithful Smoothieware: just enough G-code for jogging, zeroing and macros.

  python z1sim.py [--name Makera_Z1P_SIM] [--port 2222] [--latency-ms 0] [--no-broadcast]

  * UDP broadcast "name,ip,port,busy,version" to 255.255.255.255:3333 once a second.
  * TCP 2222, several clients at once. Every frame is logged.
  * '?' -> 0x81 status, '!' hold, '~' resume, 0x18 halt (-> Alarm, needs $X), 0x19 stop (flush queue).
  * Lines: G0/G1 (G90/G91, F), G4, G10 L20 P0, G92, G53, $H, $X, M220 S, M3/M5 S, M6 T, M999, anything
    else just gets "ok". Replies are 0x90 "ok\\r\\n".
  * Motion runs in real time at the commanded feed (G0 = axis max rate) x feed override, with a
    32-block planner queue; soft limits X -207..-1, Y -206..-1, Z -102..-1 once homed;
    $H ends at MPos -1 on X, Y and Z, like the real Z1.
"""
from __future__ import annotations

import argparse
import math
import re
import socket
import threading
import time
from collections import deque

import z1proto as P

AXES = "XYZA"
MAX_RATE = {"X": 3000.0, "Y": 3000.0, "Z": 1000.0, "A": 1800.0}   # mm/min (deg/min for A)
SOFT = {"X": (-207.0, -1.0), "Y": (-206.0, -1.0), "Z": (-102.0, -1.0)}
QUEUE_MAX = 32
QUEUE_DELAY_S = 0.100   # Smoothie conveyor queue_delay_time_ms default: a queue that was empty waits this long


def log(msg):
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}", flush=True)


class Machine:
    def __init__(self):
        self.lock = threading.RLock()
        self.mpos = [-100.0, -100.0, -50.0, 0.0]
        self.wco = [0.0, 0.0, 0.0, 0.0]          # work offset: wpos = mpos - wco
        self.absolute = True
        self.feed = 500.0
        self.fro = 100.0
        self.spindle_target = 10000.0          # the real Z1 reports its default target while off
        self.spindle_factor = 100.0            # M223 spindle override, %
        self.spindle_on = False
        self.spindle_cur = 0.0
        self.spindle_at = time.monotonic()
        self.tool = 1                          # active tool (M6 T…); 0 = none: M3 then halts, like the Z1
        self.light = True
        self.vacuum = False
        self.state = "Idle"
        self.homed = False
        self.hold = False
        self.queue: deque = deque()               # each: (target list, feed mm/min) or ("dwell", s)
        self.cur = None
        self.msgs: list[str] = []                 # pushed to clients as 0x90
        self.wait_since = None                    # when the first block arrived in an empty queue
        self.starts = 0                           # restarts from an empty queue (stop-go count)

    # --- G-code -------------------------------------------------------------------------
    def line(self, text: str) -> list[str]:
        t = text.strip().upper().replace(" ", "")
        if not t:
            return ["ok\r\n"]
        with self.lock:
            if t in ("$X", "M999"):
                if self.state == "Alarm":
                    self.state = "Idle"
                return ["ok\r\n"]
            if self.state == "Alarm":
                return ["error:Alarm lock\r\n"]
            if t == "$H":
                self.queue.clear()
                self.cur = None
                self.state = "Home"
                threading.Thread(target=self._home, daemon=True).start()
                return ["ok\r\n"]
            words = re.findall(r"([A-Z])([-+]?\d*\.?\d*)", t)
            g = [float(v) for k, v in words if k == "G" and v]
            m = [float(v) for k, v in words if k == "M" and v]
            vals = {}
            for k, v in words:
                if k not in "GM" and v not in ("", "+", "-", "."):
                    vals[k] = float(v)
            if 90 in g:
                self.absolute = True
            if 91 in g:
                self.absolute = False
            if "F" in vals and not (10 in g or 4 in g):
                self.feed = vals["F"]
            if 223 in m and "S" in vals:
                self._spin()
                self.spindle_factor = max(10.0, min(300.0, vals["S"]))
                return ["ok\r\n"]
            if 220 in m and "S" in vals:
                self.fro = max(10.0, min(300.0, vals["S"]))
                return ["ok\r\n"]
            if 821 in m:
                self.light = True
            if 822 in m:
                self.light = False
            if 851 in m:
                self.vacuum = True
            if 852 in m:
                self.vacuum = False
            if 6 in m and "T" in vals:
                self.tool = int(vals["T"])
            if 3 in m or 4 in m:
                if self.tool <= 0 or self.tool >= 100000:
                    self.halt()
                    return ["ERROR: No tool or probe tool!\r\n"]
                self._spin()
                self.spindle_target = vals.get("S", self.spindle_target)
                self.spindle_on = True
            if 5 in m:
                self._spin()
                self.spindle_on = False
            if 10 in g and vals.get("L") == 20:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 92 in g:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 4 in g:
                self.queue.append(("dwell", vals.get("P", 0) / 1000.0 if "P" in vals else vals.get("S", 0)))
                return ["ok\r\n"]
            if any(ax in vals for ax in AXES) and (0 in g or 1 in g or not g or 53 in g or 90 in g or 91 in g):
                start = self._end_pos()
                tgt = list(start)
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        if 53 in g:
                            tgt[i] = vals[ax]
                        elif self.absolute:
                            tgt[i] = vals[ax] + self.wco[i]
                        else:
                            tgt[i] = start[i] + vals[ax]
                if self.homed:
                    for i, ax in enumerate("XYZ"):
                        lo, hi = SOFT[ax]
                        if not (lo - 1e-6 <= tgt[i] <= hi + 1e-6):
                            self._alarm(f"Soft Endstop {ax} was exceeded - reset or $X or M999 required")
                            return ["error:Soft Endstop\r\n"]
                rapid = 0 in g and 1 not in g
                while len(self.queue) >= QUEUE_MAX:          # firmware blocks before 'ok' when full
                    self.lock.release()
                    time.sleep(0.01)
                    self.lock.acquire()
                self.queue.append((tgt, None if rapid else self.feed))
                return ["ok\r\n"]
            return ["ok\r\n"]

    def _end_pos(self):
        for item in reversed(self.queue):
            if item[0] != "dwell":
                return list(item[0])
        if self.cur and self.cur[0] != "dwell":
            return list(self.cur[0])
        return list(self.mpos)

    def _alarm(self, msg):
        self.queue.clear()
        self.cur = None
        self.state = "Alarm"
        self.msgs.append(f"ALARM: {msg}\r\n")

    def halt(self):
        with self.lock:
            self._alarm("Abort during cycle")

    def stop(self):
        with self.lock:
            self.queue.clear()
            self.cur = None

    def _home(self):
        while True:
            with self.lock:
                if self.state != "Home":
                    return
                done = True
                for i, ax in enumerate("XYZ"):
                    # The real Z1 homes to MPos -1.000 (the soft max); 0 would be past it,
                    # so the next move on any axis would trip the soft limit.
                    home = SOFT[ax][1]
                    step = 3000 / 60 * 0.01
                    if self.mpos[i] != home:
                        d = home - self.mpos[i]
                        self.mpos[i] = home if abs(d) <= step else self.mpos[i] + math.copysign(step, d)
                        done = False
                if done:
                    self.homed = True
                    self.state = "Idle"
                    return
            time.sleep(0.01)

    # --- motion thread ------------------------------------------------------------------
    def run(self):
        dt = 0.005
        dwell_end = 0.0
        while True:
            time.sleep(dt)
            with self.lock:
                if self.state in ("Alarm", "Home"):
                    continue
                if self.hold:
                    self.state = "Hold"
                    continue
                if self.cur is None and self.queue:
                    if self.state != "Run":
                        # empty -> non-empty: Smoothie holds off QUEUE_DELAY_S unless the queue fills
                        if self.wait_since is None:
                            self.wait_since = time.time()
                        if time.time() - self.wait_since < QUEUE_DELAY_S and len(self.queue) < QUEUE_MAX:
                            continue
                        self.wait_since = None
                        self.starts += 1
                        log(f"START #{self.starts} (queue {len(self.queue)})")
                    self.cur = self.queue.popleft()
                    if self.cur[0] == "dwell":
                        dwell_end = time.time() + self.cur[1]
                if self.cur is None:
                    self.state = "Idle"
                    continue
                self.state = "Run"
                if self.cur[0] == "dwell":
                    if time.time() >= dwell_end:
                        self.cur = None
                    continue
                tgt, feed = self.cur
                d = [tgt[i] - self.mpos[i] for i in range(4)]
                dist = math.sqrt(sum(x * x for x in d))
                if dist < 1e-6:
                    self.mpos = list(tgt)
                    self.cur = None
                    continue
                if feed is None:
                    feed = min(MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9)
                else:
                    feed = feed * self.fro / 100.0
                    feed = min([feed] + [MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9])
                step = feed / 60.0 * dt
                if step >= dist:
                    self.mpos = list(tgt)
                    self.cur = None
                else:
                    self.mpos = [self.mpos[i] + d[i] / dist * step for i in range(4)]

    def _spin(self):
        """Ramp the measured RPM toward the target (on) or 0 (off), about 3000 RPM/s."""
        now = time.monotonic()
        goal = min(13000.0, self.spindle_target * self.spindle_factor / 100) if self.spindle_on else 0.0
        step = 3000.0 * (now - self.spindle_at)
        self.spindle_at = now
        d = goal - self.spindle_cur
        self.spindle_cur = goal if abs(d) <= step else self.spindle_cur + math.copysign(step, d)

    def status(self) -> str:
        with self.lock:
            self._spin()
            mp = self.mpos
            wp = [mp[i] - self.wco[i] for i in range(4)]
            f = self.cur[1] if self.cur and self.cur[0] != "dwell" and self.cur[1] else 0.0
            return (f"<{self.state}|MPos:{mp[0]:.4f},{mp[1]:.4f},{mp[2]:.4f},{mp[3]:.4f},0.0000"
                    f"|WPos:{wp[0]:.4f},{wp[1]:.4f},{wp[2]:.4f},{wp[3]:.4f},0.0000"
                    f"|F:{f:.1f},{self.feed:.1f},{self.fro:.1f}|S:{self.spindle_cur:.1f},{self.spindle_target:.1f},{self.spindle_factor:.1f},0"
                    f"|T:{self.tool},0.000,-1|W:0.00>")


class Client(threading.Thread):
    def __init__(self, sock, addr, machine: Machine, latency: float):
        super().__init__(daemon=True)
        self.sock, self.addr, self.m, self.latency = sock, addr, machine, latency
        self.dec = P.Decoder()
        self.wlock = threading.Lock()
        self.lines: deque = deque()
        self.ev = threading.Event()

    def tx(self, ptype, text: str):
        if self.latency:
            time.sleep(self.latency)
        with self.wlock:
            try:
                self.sock.sendall(P.build(ptype, text.encode()))
            except OSError:
                pass

    def worker(self):
        # G-code lines are handled in order on their own thread (like the firmware's main loop),
        # so a full planner queue delays 'ok' without blocking real-time bytes.
        while True:
            self.ev.wait()
            self.ev.clear()
            while self.lines:
                line = self.lines.popleft()
                for r in self.m.line(line):
                    self.tx(P.NORMAL_INFO, r)

    def run(self):
        log(f"client {self.addr} connected")
        threading.Thread(target=self.worker, daemon=True).start()
        last_ver = 0.0
        self.sock.settimeout(0.1)
        while True:
            if time.time() - last_ver > 5:
                self.tx(P.FIRM_VER, "SIM-1.0")
                last_ver = time.time()
            while self.m.msgs:
                self.tx(P.NORMAL_INFO, self.m.msgs.pop(0))
            try:
                data = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data:
                break
            for ptype, payload in self.dec.feed(data):
                if ptype == P.CTRL_SINGLE and payload:
                    c = payload[:1]
                    if c == b"?":
                        self.tx(P.STATUS_RES, self.m.status())
                        continue
                    log(f"{self.addr} RT {c!r}")
                    if c == P.RT_HOLD:
                        self.m.hold = True
                    elif c == P.RT_RESUME:
                        self.m.hold = False
                    elif c == P.RT_HALT:
                        self.m.halt()
                        self.tx(P.NORMAL_INFO, "ERROR: Abort during cycle\r\n")
                    elif c == P.RT_STOP:
                        self.m.stop()
                elif ptype == P.CTRL_MULTI and payload.strip() == b"diagnose":
                    # like the firmware: answered with 0x82, no "ok"
                    self.tx(P.DIAG_RES, "{S:0,10000,0,0,25,23|L:0,0|V:1,28|F:0,0|G:%d,0,0,%d,%d|T:0|C:1|E:0,0,0,0,0,1,1,0|P:0,0|I:0|RSSI:-60}"
                            % (1 if self.m.light else 0, 1 if self.m.vacuum else 0, 80 if self.m.vacuum else 0))
                elif ptype == P.CTRL_MULTI:
                    line = payload.decode("ascii", "replace")
                    log(f"{self.addr} LINE {line!r}")
                    self.lines.append(line)
                    self.ev.set()
                else:
                    log(f"{self.addr} type 0x{ptype:02X} {payload!r}")
        log(f"client {self.addr} gone")
        self.sock.close()


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def broadcaster(name, port):
    # Bind to the LAN address and send to its /24 broadcast: on a multi-homed host,
    # 255.255.255.255 leaves by whichever interface the routing table picks.
    ip = local_ip()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind((ip, 0))
    bcast = ip.rsplit(".", 1)[0] + ".255"
    while True:
        msg = f"{name},{ip},{port},0,SIM-1.0".encode()
        try:
            s.sendto(msg, (bcast, P.UDP_DISCOVERY_PORT))
        except OSError as e:
            log(f"broadcast failed: {e}")
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="Makera_Z1P_SIM")
    ap.add_argument("--port", type=int, default=P.TCP_PORT)
    ap.add_argument("--latency-ms", type=float, default=0)
    ap.add_argument("--no-broadcast", action="store_true")
    a = ap.parse_args()
    m = Machine()
    threading.Thread(target=m.run, daemon=True).start()
    if not a.no_broadcast:
        threading.Thread(target=broadcaster, args=(a.name, a.port), daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.port))
    srv.listen(4)
    log(f"sim '{a.name}' on {local_ip()}:{a.port}")
    while True:
        sock, addr = srv.accept()
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        Client(sock, addr, m, a.latency_ms / 1000).start()


if __name__ == "__main__":
    main()
