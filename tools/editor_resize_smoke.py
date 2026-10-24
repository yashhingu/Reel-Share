"""Check the actual rendered picture, not just Compose bounds.

Requires adb, Python and Pillow. Open an editor with generated test footage, pause
it away from a transition, and leave the preview collapsed. No draft is changed.
Run: python tools/editor_resize_smoke.py --serial emulator-5582
Screenshots are written under the ignored build/ directory.
"""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageStat


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", default="build/resize-smoke")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.serial, *command], timeout=30)

    def nodes():
        result = adb("shell", "uiautomator", "dump", "/sdcard/reelshare-resize-check.xml")
        assert b"dumped to" in result, result.decode(errors="replace")
        return list(ET.fromstring(adb("shell", "cat", "/sdcard/reelshare-resize-check.xml")).iter("node"))

    def find(label):
        return next(n for n in nodes() if n.get("content-desc") == label)

    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(label):
        left, top, right, bottom = bounds(find(label))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(.8)

    def capture(name):
        frame = bounds(find("Video frame"))
        picture = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p"))).convert("RGB")
        picture.save(output / f"{name}.png")
        return frame, picture.crop(frame)

    assert any(n.get("content-desc") == "Play preview" for n in nodes()), "Pause the preview first."
    initial, before = capture("collapsed")
    assert max(ImageStat.Stat(before).stddev) > 10, "Use a visible, varied test frame; a blank surface cannot verify resizing."
    try:
        tap("Expand video preview")
        expanded, large = capture("expanded")
    finally:
        if any(n.get("content-desc") == "Show editing controls" for n in nodes()):
            tap("Show editing controls")
    restored, after = capture("restored")
    assert restored == initial, (initial, restored)
    # Ignore the outer edge, where rounded outlines and scaling can differ by a pixel.
    def normal(image):
        scaled = image.resize((300, 300), Image.Resampling.BILINEAR)
        return scaled.crop((4, 4, 296, 296))
    def difference(a, b):
        return max(ImageStat.Stat(ImageChops.difference(normal(a), normal(b))).mean)
    expand_error, restore_error = difference(before, large), difference(before, after)
    result = dict(collapsed=initial, expanded=expanded, restored=restored,
                  expanded_pixel_error=expand_error, restored_pixel_error=restore_error)
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    assert expand_error < 8, f"Expanded picture differs: {expand_error:.2f}/255"
    assert restore_error < 2, f"Collapsed picture differs: {restore_error:.2f}/255"
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
