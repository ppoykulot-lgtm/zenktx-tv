#!/usr/bin/env python3
"""Draws the ZENKTX mark and every bitmap asset that ships with it.

The mark is one geometry, described once in 512x512 units, and used by:

  * android/app/src/main/res/drawable/ic_launcher.xml   (in-app icon, vector)
  * android/app/src/main/res/drawable/ic_mark.xml       (header + welcome, vector)
  * android/app/src/main/res/drawable-xhdpi/tv_banner.png
  * windows/assets/icon.png, windows/build/icon.png, windows/build/icon.ico
  * the favicon on the site (docs/index.html, inline SVG)

Why a script instead of a drawing tool: the PNG files are derived from the same
numbers as the vector, so a size change cannot leave the launcher icon and the
desktop icon looking like two different logos.

Geometry (512 units, y down)
  tile      rounded square, r=112, fill #0A1020
  Z         mint cap, mint diagonal, blue base
  signal    two concentric arcs centred on the play triangle, inner mint,
            outer blue; the arcs replace the blue scribble that used to sit
            over them and made the old icon look hand-drawn
  play      white triangle, its tip pointing into the arcs
  base      two short rounded bars under the Z

Run: python tools/make-icons.py [--preview .chk/icon-preview.png]
"""

from __future__ import annotations

import argparse
import os

from PIL import Image, ImageDraw, ImageFont

BG = (10, 16, 32, 255)          # #0A1020
MINT = (53, 231, 192, 255)      # #35E7C0
BLUE = (91, 140, 255, 255)      # #5B8CFF
WHITE = (242, 245, 255, 255)    # #F2F5FF

TILE_R = 112
# Z. The diagonal starts just inside the cap and lands inside the base, so the
# three strokes fuse instead of leaving a bump above the top bar.
CAP = (96, 150, 272, 196)                   # x0, y0, x1, y1
DIAGONAL = ((245, 173), (140, 296))
BASE = (96, 290, 252, 336)
STROKE = 44
# Signal arcs, centred on the play triangle: the picture is the source, the
# rings are what it broadcasts.
ARC_C = (318, 316)
ARC_INNER = 72
ARC_OUTER = 106
ARC_START = -104.0
ARC_END = -14.0
TIME_INNER = 18
TIME_OUTER = 15
# Play triangle
PLAY = ((296, 292), (350, 318), (296, 344))
# Underline accents
ACCENTS = (((152, 374, 224, 382), MINT), ((234, 374, 288, 382), BLUE))

SS = 4  # supersampling factor


def scaled(value: float, size: int) -> float:
    return value * size / 512.0


def draw_mark(size: int, tile: bool = True, background: bool = True) -> Image.Image:
    """Returns an RGBA image of the mark at `size` pixels."""
    big = size * SS
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0) if not background else BG)
    draw = ImageDraw.Draw(image)

    if tile:
        draw.rounded_rectangle(
            (0, 0, big - 1, big - 1), radius=scaled(TILE_R, size) * SS, fill=BG
        )

    # ---- signal arcs (under the Z, so the Z always reads first) ------------
    def arc(radius: float, colour, width: float, alpha: int) -> None:
        pad = scaled(radius, size) * SS
        box = (
            scaled(ARC_C[0], size) * SS - pad,
            scaled(ARC_C[1], size) * SS - pad,
            scaled(ARC_C[0], size) * SS + pad,
            scaled(ARC_C[1], size) * SS + pad,
        )
        # PIL measures clockwise from 3 o'clock, like SVG, so the same angles
        # describe the same sweep.
        draw.arc(
            box,
            start=(ARC_START + 360) % 360,
            end=(ARC_END + 360) % 360,
            fill=colour[:3] + (alpha,),
            width=max(1, round(scaled(width, size) * SS)),
        )

    arc(ARC_OUTER, BLUE, TIME_OUTER, 235)
    arc(ARC_INNER, MINT, TIME_INNER, 245)

    # ---- play triangle ----------------------------------------------------
    points = [(scaled(x, size) * SS, scaled(y, size) * SS) for x, y in PLAY]
    draw.polygon(points, fill=WHITE)

    # ---- Z ----------------------------------------------------------------
    def bar(box, colour) -> None:
        x0, y0, x1, y1 = box
        draw.rounded_rectangle(
            (scaled(x0, size) * SS, scaled(y0, size) * SS,
             scaled(x1, size) * SS, scaled(y1, size) * SS),
            radius=scaled((y1 - y0) / 2, size) * SS,
            fill=colour,
        )

    bar(BASE, BLUE)
    bar(CAP, MINT)
    width = round(scaled(STROKE, size) * SS)
    start = (scaled(DIAGONAL[0][0], size) * SS, scaled(DIAGONAL[0][1], size) * SS)
    end = (scaled(DIAGONAL[1][0], size) * SS, scaled(DIAGONAL[1][1], size) * SS)
    draw.line([start, end], fill=MINT, width=width)
    for point in (start, end):  # round caps
        r = width / 2
        draw.ellipse((point[0] - r, point[1] - r, point[0] + r, point[1] + r), fill=MINT)

    # ---- baseline accents -------------------------------------------------
    for box, colour in ACCENTS:
        bar(box, colour)

    return image.resize((size, size), Image.LANCZOS)


def tv_banner(width: int = 320, height: int = 180) -> Image.Image:
    """Android TV launcher banner: the mark on the left, the wordmark on the right."""
    image = Image.new("RGBA", (width, height), BG)

    # A soft mint wash from the left so the banner is not a flat rectangle. It
    # is composited, because ImageDraw writes RGBA values as they are instead of
    # blending them, which made the whole banner see-through.
    wash = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    wash_draw = ImageDraw.Draw(wash)
    for x in range(width):
        wash_draw.line([(x, 0), (x, height)], fill=(53, 231, 192, int(30 * (1 - x / width) ** 2)))
    image.alpha_composite(wash)

    image.alpha_composite(draw_mark(height - 60, tile=False, background=False), (32, 30))

    draw = ImageDraw.Draw(image)
    font_path = r"C:\Windows\Fonts\arialbd.ttf"
    word = ImageFont.truetype(font_path, 38) if os.path.exists(font_path) else None
    sub = ImageFont.truetype(font_path, 13) if os.path.exists(font_path) else None
    draw.text((136, 62), "ZENKTX", font=word, fill=WHITE)
    draw.text((138, 110), "T V   S T R E A M", font=sub, fill=MINT)
    return image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="also write a 512px preview PNG here")
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    def write(relative: str, image: Image.Image) -> None:
        target = os.path.join(root, relative)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if target.endswith(".ico"):
            image.save(target, sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                      (64, 64), (128, 128), (256, 256)])
        else:
            image.save(target, "PNG", optimize=True)
        print(f"{relative}: {os.path.getsize(target):,} bytes")

    icon = draw_mark(512)
    write("windows/assets/icon.png", icon)
    write("windows/build/icon.png", icon)
    write("windows/build/icon.ico", draw_mark(256))
    write("android/app/src/main/res/drawable-xhdpi/tv_banner.png", tv_banner())

    if args.preview:
        icon.save(args.preview)
        print(f"{args.preview}: preview")


if __name__ == "__main__":
    main()
