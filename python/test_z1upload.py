"""z1upload.py against the simulator (z1sim.py), which answers uploads the way the ESP32's handler
(FUN_4201f750) does. Run: python test_z1upload.py"""
import hashlib
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time

import z1proto as P
import z1upload as U

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 22261


def start_sim(sd):
    p = subprocess.Popen([sys.executable, os.path.join(HERE, "z1sim.py"), "--port", str(PORT), "--no-broadcast",
                          "--speed", "20", "--sd", sd], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", PORT), 0.2).close()
            return p
        except OSError:
            time.sleep(0.1)
    p.kill()
    raise SystemExit("simulator didn't start")


def send_line(line):
    s = socket.create_connection(("127.0.0.1", PORT))
    s.sendall(P.cmd(line))
    time.sleep(0.3)
    s.close()


def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra else ""))
    if not cond:
        check.failed += 1


check.failed = 0


def main():
    sd = tempfile.mkdtemp(prefix="z1sd_")
    work = tempfile.mkdtemp(prefix="z1up_")
    sim = start_sim(sd)
    try:
        def local(name, data):
            p = os.path.join(work, name)
            with open(p, "wb") as f:
                f.write(data)
            return p

        small = b"(upload test)\nG0 Z10\n"
        r = U.upload("127.0.0.1", local("_upload_test.cnc", small), port=PORT)
        check("small file", r.ok and open(os.path.join(sd, "_upload_test.cnc"), "rb").read() == small, r.message)
        md5 = open(os.path.join(sd, ".md5", "_upload_test.cnc"), "rb").read()
        check("md5 cache written", md5 == hashlib.md5(small).hexdigest().encode(), md5)

        big = b"".join(b"G1 X%.3f Y%.3f F1200\n" % (i * 0.01, i * 0.02) for i in range(12000))
        seen = []
        r = U.upload("127.0.0.1", local("Setup1 (2).cnc", big), port=PORT, verify=True,
                     progress=lambda d, t: seen.append(d))
        check("multi-packet, space in name, verified", r.ok and r.verified and
              open(os.path.join(sd, "Setup1 (2).cnc"), "rb").read() == big, f"{len(big)} B, {r.message}")
        check("progress reaches the end", seen and seen[-1] == len(big), seen[-1:])

        r = U.upload("127.0.0.1", local("_upload_test.cnc", b"G0 X1\n"), port=PORT)
        check("overwrite", r.ok and open(os.path.join(sd, "_upload_test.cnc"), "rb").read() == b"G0 X1\n", r.message)

        r = U.upload("127.0.0.1", local("empty.cnc", b""), port=PORT)
        check("empty file", r.ok and os.path.getsize(os.path.join(sd, "empty.cnc")) == 0, r.message)

        r = U.upload("127.0.0.1", local("x.cnc", small), "/sd/gcodes/nofolder/x.cnc", port=PORT)
        check("missing folder -> machine's error", not r.ok and "failed to open" in r.message, r.message)

        r = U.upload("127.0.0.1", local("x.cnc", small), "/sd/gcodes/bad!name.cnc", port=PORT)
        check("refuses characters the app escapes", not r.ok and "characters" in r.message, r.message)

        r = U.upload("127.0.0.1", local("x.cnc", small), port=PORT, packet_size=8192)
        check("8192-byte packets", r.ok, r.message)

        send_line("simxfer drop 3")
        t0 = time.time()
        r = U.upload("127.0.0.1", local("drop.cnc", big), port=PORT, verify=True)
        check("lost packet is resent", r.ok and r.verified, f"{r.message}, {time.time() - t0:.1f} s")

        # A client drops mid-upload: its upload lingers and the next client gets its requests.
        # upload() cancels it and starts its own.
        import hashlib as _h, struct as _s
        s = socket.create_connection(("127.0.0.1", PORT))
        s.sendall(P.build(0xB0, b"upload /sd/gcodes/partial.cnc\n") + P.build(0xB1, _h.md5(big).hexdigest().encode()))
        time.sleep(0.3)
        s.sendall(P.build(0xB2, _s.pack(">IH", 76, 4096)))
        time.sleep(0.3)
        s.sendall(P.build(0xB3, _s.pack(">I", 1) + big[:4096]))
        time.sleep(0.3)
        s.close()
        time.sleep(0.2)
        r = U.upload("127.0.0.1", local("after_drop.cnc", small), port=PORT, verify=True)
        check("recovers from a dropped client's upload, read-back too", r.ok and r.verified and r.seconds < 3 and
              open(os.path.join(sd, "after_drop.cnc"), "rb").read() == small, f"{r.message}, {r.seconds:.1f} s")
        r = U.upload("127.0.0.1", local("after_drop_next.cnc", small), port=PORT, verify=True, busy_wait=0)
        check("and the next upload straight after", r.ok and r.seconds < 3, f"{r.message}, {r.seconds:.1f} s")
        check("the cancelled upload's file is removed (like the board)", not os.path.exists(os.path.join(sd, "partial.cnc")))

        # Same, but the dropped upload was waiting for its file view: our data must not end up in it.
        s = socket.create_connection(("127.0.0.1", PORT))
        s.sendall(P.build(0xB0, b"upload /sd/gcodes/partial2.cnc\n") + P.build(0xB1, b"0" * 32))
        time.sleep(0.3)
        s.close()
        time.sleep(0.2)
        r = U.upload("127.0.0.1", local("after_drop2.cnc", big), port=PORT, verify=True)
        check("dropped upload waiting for its view: ours still lands right", r.ok and r.verified and
              open(os.path.join(sd, "after_drop2.cnc"), "rb").read() == big, r.message)

        # Another client holds the transfer slot: we get 0x91 and report it.
        hold = socket.create_connection(("127.0.0.1", PORT))
        hold.sendall(P.build(0xB0, b"download /sd/gcodes/_upload_test.cnc\n"))
        time.sleep(0.3)
        r = U.upload("127.0.0.1", local("busy.cnc", small), port=PORT, busy_wait=0)
        check("busy (another client transferring)", not r.ok and "another app" in r.message, r.message)
        threading.Timer(2.0, lambda: (hold.sendall(P.build(0xB5)), time.sleep(0.3), hold.close())).start()
        t0 = time.time()
        r = U.upload("127.0.0.1", local("busy.cnc", small), port=PORT)
        check("waits while busy, then sends", r.ok and 1.5 < time.time() - t0 < 6, f"{r.message}, {time.time() - t0:.1f} s")

        # A job is running: refused; and never overwrite the playing file even when allowed.
        job = local("job.cnc", b"".join(b"G1 X%d F600\n" % (i % 50) for i in range(400)))
        check("job uploaded", U.upload("127.0.0.1", job, port=PORT).ok)
        send_line("$H")
        time.sleep(1.5)
        send_line("play /sd/gcodes/job.cnc")
        time.sleep(0.5)
        r = U.upload("127.0.0.1", local("other.cnc", small), port=PORT)
        check("refused while a job runs", not r.ok and "busy" in r.message, r.message)
        r = U.upload("127.0.0.1", job, port=PORT, allow_during_job=True)
        check("never overwrites the playing file", not r.ok and "playing" in r.message, r.message)
        send_line("\x19")
    finally:
        sim.kill()
    print("ALL PASS" if not check.failed else f"{check.failed} FAILED")
    return 1 if check.failed else 0


if __name__ == "__main__":
    sys.exit(main())
