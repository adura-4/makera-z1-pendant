"""Read-only job queries for a running job: which file is playing (0xB7, answered by the ESP32)
and the firmware's `progress` text. Nothing here moves the machine.

    python job_queries.py <machine ip>
"""
import sys
import time

import z1client as C
import z1proto as P

ip = sys.argv[1]
c = C.Conn(ip, tag="[Q] ")
c.quiet_status = True
time.sleep(1.0)
c.send(P.build(0xB7, b""), "0xB7 (running file name, empty payload)")
time.sleep(3.0)
c.send(P.cmd("progress"), "progress")
time.sleep(3.0)
c.close()
