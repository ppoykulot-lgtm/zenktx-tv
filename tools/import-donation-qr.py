#!/usr/bin/env python3
"""Install a payment QR screenshot into every app that shows it.

Drop the picture you took in the GCash or Maya app into

    inbox/gcash.png
    inbox/maya.png

and run

    python tools/import-donation-qr.py

The script finds the QR code inside the picture (phones love to add a header,
a "Save" button and a dark background), crops to it, lays it on a quiet white
margin at 1024 px, checks that the finished file still scans, and copies the
result to

    android/app/src/main/assets/donation/{gcash,maya}.png
    windows/assets/donation/{gcash,maya}.png
    docs/assets/donation/{gcash,maya}.png

Only the cropped QR leaves the inbox: the screenshot with your name, balance or
phone chrome in it is never shipped.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import cv2
    import numpy as np
except ImportError as exc:  # pragma: no cover - setup hint
    sys.exit(f"missing dependency ({exc.name}); run: pip install opencv-python-headless")

ROOT = Path(__file__).resolve().parent.parent
INBOX = ROOT / "inbox"
TARGETS = (
    ROOT / "android/app/src/main/assets/donation",
    ROOT / "windows/assets/donation",
    ROOT / "docs/assets/donation",
)
SIZE = 1024        # finished image is SIZE x SIZE
QUIET = 0.12       # white margin each side, well past the 4-module quiet zone

WANTED = {"gcash": "GCash", "maya": "Maya"}

# OpenCV reports a box that can sit inside the symbol, so grow it until the
# grown crop decodes to the same payload.
GROWTH = (1.0, 1.08, 1.16, 1.25, 1.35, 1.5, 1.7, 1.9, 2.2, 2.6)


def detector() -> "cv2.QRCodeDetector":
    return cv2.QRCodeDetector()


def decode(image: np.ndarray) -> tuple[str | None, np.ndarray | None]:
    text, points, _ = detector().detectAndDecode(image)
    if points is None:
        big = cv2.resize(image, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
        text, points, _ = detector().detectAndDecode(big)
        if points is not None:
            points = points / 2.0
    return (text or None), points


def crop_around(image: np.ndarray, points: np.ndarray, scale: float) -> np.ndarray:
    pts = np.asarray(points).reshape(-1, 2)
    x0, y0 = pts[:, 0].min(), pts[:, 1].min()
    x1, y1 = pts[:, 0].max(), pts[:, 1].max()
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    side = max(x1 - x0, y1 - y0) * scale

    left = int(max(cx - side / 2.0, 0))
    top = int(max(cy - side / 2.0, 0))
    right = int(min(cx + side / 2.0, image.shape[1]))
    bottom = int(min(cy + side / 2.0, image.shape[0]))
    crop = image[top:bottom, left:right]

    # Square it with white, never with whatever page colour was around it.
    edge = max(crop.shape[0], crop.shape[1])
    square = np.full((edge, edge, 3), 255, np.uint8)
    dy = (edge - crop.shape[0]) // 2
    dx = (edge - crop.shape[1]) // 2
    square[dy:dy + crop.shape[0], dx:dx + crop.shape[1]] = crop
    return square


def find_crop(image: np.ndarray, text: str, points: np.ndarray) -> np.ndarray | None:
    for scale in GROWTH:
        crop = crop_around(image, points, scale)
        if decode(crop)[0] == text:
            return crop
    return None


def build(name: str, source: Path) -> bool:
    raw = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if raw is None:
        print(f"  {name}: cannot read {source}")
        return False

    # Phone screenshots are tall; a plain resize speeds detection up a lot.
    if max(raw.shape[:2]) > 2400:
        raw = cv2.resize(raw, None, fx=2400 / max(raw.shape[:2]), fy=2400 / max(raw.shape[:2]),
                         interpolation=cv2.INTER_AREA)

    text, points = decode(raw)
    if points is None:
        print(f"  {name}: no QR code found in {source.name} — crop it by hand?")
        return False
    if not text:
        print(f"  {name}: found a code but cannot decode it (blurry or cut off)")
        return False

    crop = find_crop(raw, text, points)
    if crop is None:
        print(f"  {name}: could not isolate a scannable crop from {source.name}")
        return False

    quiet = int(SIZE * QUIET)
    inner = SIZE - quiet * 2
    crop = cv2.resize(crop, (inner, inner), interpolation=cv2.INTER_AREA)
    canvas = np.full((SIZE, SIZE, 3), 255, np.uint8)
    canvas[quiet:quiet + inner, quiet:quiet + inner] = crop

    # Re-check the finished file, not the crop.
    if decode(canvas)[0] != text:
        print(f"  {name}: finished code no longer matches the source image")
        return False

    for folder in TARGETS:
        folder.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(folder / f"{name}.png"), canvas)

    preview = text if len(text) <= 24 else f"{text[:6]}…({len(text)} chars)"
    print(f"  {name}: installed {SIZE}x{SIZE} from {source.name} (payload {preview})")
    return True


def main() -> int:
    if not INBOX.exists():
        print(f"no {INBOX} folder")
        return 1

    done, missing = 0, []
    for slug, label in WANTED.items():
        source = next((p for p in sorted(INBOX.glob(f"{slug}.*"))
                       if p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"}), None)
        if source is None:
            missing.append(label)
            continue
        print(f"{label}:")
        done += 1 if build(slug, source) else 0

    for label in missing:
        print(f"still waiting for {label}: save it as inbox/{label.lower()}.png")
    print(f"{done} of {len(WANTED)} QR codes installed")
    return 0 if done else 1


if __name__ == "__main__":
    raise SystemExit(main())
