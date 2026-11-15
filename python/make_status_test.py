"""Write a safe ~5 minute test job for reading what the Z1 reports while a job runs.

No cutting. Moves are in work coordinates (G54) near the work zero, like a normal job, because
Studio reads the file's extents and the firmware's start sequence (M495 "go to path origin")
moves there in work coordinates: X/Y between 10 and 20, Z between 5 and 15 mm above work Z0.
(An earlier version used G53 machine coordinates; Studio took them as work coordinates and the
path-origin move hit the X soft limit.) The spindle steps up 100 RPM at a time, with two tool changes on the way (they prompt in
Studio and measure the tool on the setter). The header's tool table is copied from a real Fusion
(Makera Community Post) file, so a controller sees the real comment format.

    python make_status_test.py <real .cnc for the header> <out.cnc>
"""
import sys

src, out = sys.argv[1], sys.argv[2]
header = []
for line in open(src, encoding="utf-8", errors="replace"):
    if line.startswith("G90"):          # header ends where the program starts
        break
    header.append(line.rstrip("\n"))
header[0] = "(Pendant status test - no cutting, safe moves near the top)"

X0, Y0, ZTOP = 10.0, 10.0, 15.0        # work coordinates (G54): near X0 Y0, Z 15 above Z0
FEED = 1000                             # mm/min (deg/min for A)
AXES = [("X", X0), ("Y", Y0), ("Z", ZTOP)]

g = header + ["", "G90 G94", "G17", "G21", "G54", ""]
step = 0


def ramp(rpms, label):
    """One spindle step per RPM: set S, a +10/-10 move on the next axis, a short pause."""
    global step
    for rpm in rpms:
        step += 1
        ax, home = AXES[step % 3]
        # Z goes down 10 (to 5 above Z0) and back; X/Y go +10 and back.
        away = home - 10 if ax == "Z" else home + 10
        g.append(f"(step {step}: {label} S{rpm} {ax})")
        g.append(f"S{rpm}")
        g.append(f"G1 {ax}{away:g} F{FEED}")
        g.append(f"G1 {ax}{home:g} F{FEED}")
        g.append("G4 S1")             # S = whole seconds (P would be ms, or s in grbl mode)


def tool_change(t, rpm):
    g.extend([f"(tool change to T{t})", "M5", "M400", f"T{t}M6", "M400",
              f"S{rpm} M3", "G54", f"G0 Z{ZTOP:g}", f"G0 X{X0:g} Y{Y0:g}"])


tool_change(1, 6000)
ramp(range(6000, 9001, 100), "up")
tool_change(2, 9000)
ramp(range(9000, 12001, 100), "up")
tool_change(3, 12000)
ramp(range(12000, 5999, -100), "down")
g.extend(["(end)", "M5", "M400", f"G0 Z{ZTOP:g}", "M30", ""])

open(out, "w", encoding="utf-8", newline="\n").write("\n".join(g))
print(f"{out}: {len(g)} lines, {step} steps")
