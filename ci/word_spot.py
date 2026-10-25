"""Print an "x y" inside the first line of the longest paragraph on screen, to press and hold a word.

Usage: word_spot.py <uiautomator dump> <screen height>
"""
import re
import sys
import xml.etree.ElementTree as ET

path, height = sys.argv[1], int(sys.argv[2])
best = None
for n in ET.parse(path).iter("node"):
    text = n.get("text") or ""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    # A paragraph of the book: long text, wholly on screen, clear of the top and bottom bars.
    if len(text) > 80 and y1 > height * 0.12 and y2 < height * 0.85 and x2 > x1 + 200:
        if best is None or len(text) > best[0]:
            best = (len(text), x1, y1)
if best is None:
    sys.exit("no paragraph on screen")
print(best[1] + 60, best[2] + 25)
