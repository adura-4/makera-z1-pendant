"""Demo helper for the spindle temperature graph: sends a few lines to a running simulator as a second
client. Start the simulator with heat, e.g.

    python z1sim.py --heat 25 --spindle-temp 36 --sd <dir with hot_demo.cnc>

then:
    python sim_hot_demo.py prep   # home, park, set the work zero
    python sim_hot_demo.py play   # play hot_demo.cnc
    python sim_hot_demo.py <any G-code line>
"""
import socket
import sys
import time

import z1proto as P

HOT_DEMO = """(T8  Flat 3.175  D=3.175 - flat end mill)
(T1  Ball 1/8  D=3.175 CR=1.5 - ball end mill)
G90 G94 G21
T8M6
S12000 M3
G1 X40 F120
G1 X0
T1M6
S16000 M3
G1 X40 F120
G1 X0
G1 X40
G1 X0
G1 X40
G1 X0
M30
"""


def send(lines, host="127.0.0.1", port=2222):
    s = socket.create_connection((host, port), timeout=5)
    for l in lines:
        s.sendall(P.cmd(l)); time.sleep(0.3)
    time.sleep(0.5)
    s.close()


if __name__ == "__main__":
    a = " ".join(sys.argv[1:])
    if a == "prep":
        send(["$H"]); time.sleep(4)
        send(["G53 G0 X-150 Y-120 Z-40", "G10L20P0X0Y0Z0"])
    elif a == "play":
        send(["play hot_demo.cnc"])
    elif sys.argv[1] == "write":
        open(sys.argv[2], "w").write(HOT_DEMO)
    else:
        send([a])
