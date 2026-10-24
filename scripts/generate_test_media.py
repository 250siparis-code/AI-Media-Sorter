#!/usr/bin/env python3
"""Generate isolated deterministic decoder fixtures; no user media is read."""
import pathlib
import subprocess
import sys

target = pathlib.Path(__file__).resolve().parents[1] / "app/src/androidTest/assets"
target.mkdir(parents=True, exist_ok=True)
subprocess.run([
    "ffmpeg", "-nostdin", "-y", "-f", "lavfi", "-i",
    "testsrc2=size=160x120:rate=10:duration=3",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "10",
    "-movflags", "+faststart", str(target / "test_multiframe.mp4")
], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
print("Generated test-only 3-second H.264 multiframe fixture.")
