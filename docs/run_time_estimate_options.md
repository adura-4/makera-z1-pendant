# Job run time: how Makera Studio does it, and what the pendant should do (2026-11-18)

Jimmy asked whether the G-code carries an expected run time, how Makera Studio knows the time up
front, and what the pendant would need. Not writing it into the file from the Z1 Post (Jimmy: no).

## What the pendant does now
`JobView.leftS` (AppViewModel.kt): time left = elapsed × (lines left ÷ lines done), from the line
number in the Z1's `|P:line,percent,seconds|`. Every line counts the same. Nothing before start.

## How Makera Studio does it [confirmed from strings in `C:\Program Files\Makera Studio\MakeraStudio.exe`]
- Its **Monitor** page: "Number of lines:", "Elapsed time:", "Remaining time:", "Processing
  progress", next to a **3D Preview** of the running job.
- For that preview it **downloads the G-code from the Z1** ("Downloading...", "Download completed but
  file not found") and loads it into its G-code viewer ("Loading Gcode file:", "Total lines:",
  "**Total distance:**", "updateGcodeBounds"). The preview player reports "Preview started. **Total
  time:**", "Move speed changed to:", "Old total time:" / "New total time:" (it recomputes the total
  when the speed changes), and `GCodeGLWidget::setDisplayProgress` moves the preview to the line
  being cut.
- So Studio's time is **length ÷ feed per move**, summed over the file it parsed, and the remaining
  time follows the line the machine reports. **[inferred]** the exact model (acceleration or not):
  the Windows build has no symbols; the macOS build (with C++ symbols, on mac-test) can settle it.
- `EstimatedProcessingTimeFromPathvertex` in the same binary belongs to Makera's own CAM (path4d),
  its estimate for toolpaths it generates, not the Monitor.
- The G-code itself carries no time (Jimmy's file, Makera's examples), and the firmware only reports
  elapsed seconds and percent of the file read.

## The pendant already has the file
For any running job, also one started from Studio, `trackJob` downloads the file into memory to read
its tools and line count (`downloadFile` → `JobFileParser`, cached per MD5 in memory only, nothing
written to the phone). The Run screen downloads it too, before start, for the toolpath box. So the
same parse can build a time table with no extra download, and nothing to delete afterwards.

## Checked on tonight's job (`field_test/estimate_time.py`)
`AIR_pendant_block_air_test_Setup1.cnc`, actual times from the relay log:

| | length ÷ feed | stop at every move (150 mm/s²) | actual |
|---|---|---|---|
| T1 (flat end mill, 53 lines) | 1:19 | 1:22 | ~1:20 |
| T2 (ball mill, 2410 lines) | 11:21 | 12:44 | ~11:46 |

The truth sits between the two (the Z1 blends moves: junction deviation 0.01). The midpoint is
within about 3 % here. The lines-based pace would have said about an hour left after pass 1.

## Proposal (for Jimmy to decide)
1. In the same parse that reads the tools: a cumulative estimated time per line (G1/G2/G3 length ÷
   F, arcs included; G0 at the Z1's rapid rates, X/Y 3000, Z 1000 mm/min; the midpoint of the two
   models above). G53 / firmware moves and tool-change waits not counted.
2. **Before start** (Run screen): "About 13 min of cutting · 2 tool changes".
3. **During the job**: time left = estimated time of the lines still to run × the pace so far
   (actual ÷ estimated for the lines done, once a minute or so of cutting has passed, kept within
   reasonable bounds), with the feed override from the status applied. The elapsed time already
   stops while the machine waits in a tool change.
4. Jobs started elsewhere get the same, as soon as the file is in.
