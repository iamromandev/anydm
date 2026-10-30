"""Draws anydm's app icon: a blue rounded square with a white ring and a downward arrow.

Run from cross/desktopApp: `uv run --no-project --with pillow python icons/make_icons.py` (macOS for the .icns, via iconutil).
Writes icons/anydm.png (1024), icons/anydm.ico, icons/anydm.icns and src/main/resources/icon.png (256).
"""

import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
TOP = (0x3B, 0x8B, 0xFF)
BOTTOM = (0x0A, 0x4F, 0xC4)
SUPER = 4


def draw(size: int) -> Image.Image:
    s = size * SUPER
    gradient = Image.new("RGBA", (s, s))
    shade = ImageDraw.Draw(gradient)
    for y in range(s):
        f = y / (s - 1)
        shade.line([(0, y), (s, y)], fill=tuple(round(a + (b - a) * f) for a, b in zip(TOP, BOTTOM)) + (255,))
    # The macOS grid: an 824/1024 rounded square, centred.
    pad = round(s * 100 / 1024)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([pad, pad, s - pad - 1, s - pad - 1], radius=round(s * 185 / 1024), fill=255)
    icon = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    icon.paste(gradient, (0, 0), mask)
    pen = ImageDraw.Draw(icon)
    w = max(1, round(s * 58 / 1024))
    c = s / 2
    r = s * 0.25
    pen.ellipse([c - r, c - r, c + r, c + r], outline="white", width=w)
    tip = c + r * 0.48
    pen.line([(c, c - r * 0.55), (c, tip)], fill="white", width=w)
    arm = r * 0.40
    pen.line([(c - arm, tip - arm), (c, tip)], fill="white", width=w)
    pen.line([(c + arm, tip - arm), (c, tip)], fill="white", width=w)
    return icon.resize((size, size), Image.LANCZOS)


def main() -> None:
    big = draw(1024)
    big.save(HERE / "anydm.png")
    draw(256).save(HERE.parent / "src" / "main" / "resources" / "icon.png")
    big.save(HERE / "anydm.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    if sys.platform == "darwin":
        iconset = HERE / "anydm.iconset"
        iconset.mkdir(exist_ok=True)
        for n in (16, 32, 128, 256, 512):
            draw(n).save(iconset / f"icon_{n}x{n}.png")
            draw(n * 2).save(iconset / f"icon_{n}x{n}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(HERE / "anydm.icns")], check=True)
        shutil.rmtree(iconset)


if __name__ == "__main__":
    main()
