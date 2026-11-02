"""Raster brand assets for ZENKTX.

Draws the same geometry as the Android vector (ic_mark.xml) so the icon, the
Android TV banner and the Windows icon can never drift apart.

    python tools/make_brand.py
"""

from __future__ import annotations

import math
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent

BG = (10, 16, 32, 255)
BAND = (11, 21, 38, 255)
MINT = (53, 231, 192, 255)
BLUE = (91, 140, 255, 255)
TEXT = (242, 245, 255, 255)
MUTED = (139, 152, 180, 255)
LINE = (30, 42, 66, 255)

FONT_CANDIDATES = [
    "C:/Windows/Fonts/seguisb.ttf",
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "C:/Windows/Fonts/calibrib.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def draw_mark(img: Image.Image, ox: float, oy: float, size: float, tile: bool = True) -> None:
    """Draw the ZENKTX mark in a size x size box starting at (ox, oy)."""
    draw = ImageDraw.Draw(img, "RGBA")
    k = size / 512.0

    def p(x: float, y: float) -> tuple[float, float]:
        return ox + x * k, oy + y * k

    if tile:
        draw.rounded_rectangle([p(0, 0), p(512, 512)], radius=118 * k, fill=BG, outline=LINE, width=max(1, int(2 * k)))

    # broadcast arcs (behind the letters)
    for radius, width, alpha in ((117, 9, 90), (85, 11, 230)):
        box = [p(270 - radius, 232 - radius), p(270 + radius, 232 + radius)]
        draw.arc(box, start=-80, end=6, fill=MINT[:3] + (alpha,), width=max(1, int(width * k)))

    # Z
    draw.rounded_rectangle([p(96, 150), p(272, 196)], radius=23 * k, fill=MINT)
    draw.line([p(272, 173), p(170, 311)], fill=MINT, width=max(2, int(46 * k)), joint="curve")
    for cx, cy in ((272, 173), (170, 311)):
        r = 23 * k
        draw.ellipse([p(cx - 23, cy - 23), p(cx + 23, cy + 23)], fill=MINT)
    draw.rounded_rectangle([p(96, 288), p(230, 334)], radius=23 * k, fill=BLUE)

    # underline accents
    draw.rounded_rectangle([p(150, 360), p(224, 368)], radius=4 * k, fill=MINT)
    draw.rounded_rectangle([p(236, 360), p(290, 368)], radius=4 * k, fill=BLUE)

    # play triangle
    draw.polygon([p(296, 286), p(352, 314), p(296, 342)], fill=TEXT)


def spaced(draw: ImageDraw.ImageDraw, xy, text: str, fnt, fill, tracking: float) -> float:
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += draw.textlength(ch, font=fnt) + tracking
    return x


def mark_png(size: int, path: Path) -> None:
    scale = 4
    img = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    draw_mark(img, 0, 0, size * scale)
    img = img.resize((size, size), Image.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print("wrote", path.relative_to(ROOT))


def banner(width: int, height: int, path: Path) -> None:
    scale = 4
    W, H = width * scale, height * scale
    img = Image.new("RGBA", (W, H), (7, 12, 23, 255))
    draw = ImageDraw.Draw(img, "RGBA")

    # vertical gradient, slightly lighter at the top
    for y in range(H):
        t = y / H
        colour = tuple(int(BAND[i] * (1 - t) + (7, 12, 23, 255)[i] * t) for i in range(4))
        draw.line([(0, y), (W, y)], fill=colour)

    # soft mint glow behind the mark
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [int(W * 0.01), -int(H * 0.25), int(W * 0.46), int(H * 0.95)],
        fill=(53, 231, 192, 46),
    )
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(26 * scale)))
    draw = ImageDraw.Draw(img, "RGBA")

    mark = int(H * 0.62)
    draw_mark(img, int(W * 0.055), (H - mark) // 2, mark)

    title = font(int(H * 0.235))
    sub = font(int(H * 0.088))
    tx = int(W * 0.055) + mark + int(W * 0.045)
    ty = int(H * 0.285)
    spaced(draw, (tx, ty), "ZENKTX", title, TEXT, tracking=1.2 * scale)
    spaced(draw, (tx + 2, ty + int(H * 0.26)), "TV STREAM", sub, MINT, tracking=4.4 * scale)

    img = img.resize((width, height), Image.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print("wrote", path.relative_to(ROOT))


def main() -> None:
    mark_png(512, ROOT / "logo" / "zenktx-mark-512.png")

    # Android TV launcher banner: 320x180dp, shipped at xhdpi.
    banner(640, 360, ROOT / "android/app/src/main/res/drawable-xhdpi/tv_banner.png")

    # Windows / installer icon
    icon_png = ROOT / "windows/build/icon.png"
    mark_png(512, icon_png)
    img = Image.open(icon_png)
    img.save(ROOT / "windows/build/icon.ico", sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print("wrote windows/build/icon.ico")


if __name__ == "__main__":
    main()
