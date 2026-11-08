"""Rebuild every channel logo from its original source URL.

The old flow named each file `ch0042.png` after the row's id and skipped the
download whenever a file with that name already existed. Ids are reused when
the feed reorders, so a tile whose id once belonged to another channel kept
that channel's artwork: 4k Travel wore the TV Maria logo, Adventure Earth wore
A2Z, and nothing in the catalogue said so.

Two things changed here:

  * the file name carries a hash of the source URL, so the same id with a new
    logo gets a new file instead of silently inheriting the old one;
  * avif/webp/jpeg are converted to PNG. Coil decodes AVIF only where the
    platform does (Android 12+), so those rows had no artwork at all on the
    older boxes this app targets.

Run from the repository root:

    python tools/relink-logos.py            # download and rewrite the catalog
    python tools/relink-logos.py --report   # only say what would be fetched
"""

import hashlib
import io
import json
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "docs", "catalog.json")
FEED = os.path.join(ROOT, "channels", "feed.json")
LOGO_DIR = os.path.join(ROOT, "docs", "logos")
MAP = os.path.join(LOGO_DIR, "map.json")
BASE = "https://ppoykulot-lgtm.github.io/zenktx-tv/logos/"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

KINDS = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
    "image/gif": ".gif",
    "image/svg+xml": ".svg",
    "image/avif": ".avif",
}

# Artwork wider than this is wasted: a tile is at most ~300px on a 4K screen.
MAX_WIDTH = 480


def fetch(url: str) -> tuple[bytes, str]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*",
            "Referer": "https://iyad.click/",
        },
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        body = response.read()
        kind = (response.headers.get("Content-Type") or "").split(";")[0].strip()
    if kind not in KINDS:
        raise ValueError("not an image: %s" % (kind or "unknown"))
    if len(body) < 120:
        raise ValueError("too small: %d bytes" % len(body))
    return body, KINDS[kind]


def to_png(body: bytes) -> bytes:
    image = Image.open(io.BytesIO(body))
    image.load()
    if image.mode not in ("RGBA", "RGB", "LA", "L"):
        image = image.convert("RGBA")
    if image.width > MAX_WIDTH:
        height = round(image.height * MAX_WIDTH / image.width)
        image = image.resize((MAX_WIDTH, height), Image.LANCZOS)
    out = io.BytesIO()
    image.save(out, format="PNG", optimize=True)
    return out.getvalue()


def stem_for(row: dict, source: str) -> str:
    channel_id = row.get("id")
    key = hashlib.sha1(source.encode("utf-8")).hexdigest()[:10]
    if isinstance(channel_id, int):
        return "ch%04d-%s" % (channel_id, key)
    digest = hashlib.md5(str(row.get("name", "")).encode("utf-8")).hexdigest()[:8]
    return "c-%s-%s" % (digest, key)


def existing(stem: str) -> str | None:
    for name in os.listdir(LOGO_DIR):
        if name.startswith(stem + "."):
            return name
    return None


def main() -> int:
    report_only = "--report" in sys.argv
    with io.open(CATALOG, encoding="utf-8") as handle:
        catalog = json.load(handle)
    with io.open(FEED, encoding="utf-8") as handle:
        feed = {str(item.get("id")): item for item in json.load(handle)}

    os.makedirs(LOGO_DIR, exist_ok=True)
    mapping = {}
    if os.path.exists(MAP):
        with io.open(MAP, encoding="utf-8") as handle:
            mapping = json.load(handle)

    downloaded = reused = failed = 0
    failures = []
    kept_old = []

    for row in catalog["channels"]:
        source = (feed.get(str(row.get("id")), {}).get("logo") or "").strip()
        if not source and not str(row.get("logo", "")).startswith(BASE):
            source = (row.get("logo") or "").strip()

        if not source:
            row["logo"] = ""
            continue

        stem = stem_for(row, source)
        hit = existing(stem)
        if hit:
            row["logo"] = BASE + hit
            mapping[hit] = {"id": row.get("id"), "name": row.get("name"), "source": source}
            reused += 1
            continue

        if report_only:
            failed += 1
            failures.append((row.get("name"), "missing"))
            continue

        try:
            body, ext = fetch(source)
            if ext in (".avif", ".webp", ".jpg", ".gif"):
                body = to_png(body)
                ext = ".png"
        except Exception as error:  # noqa: BLE001 - every host failure is reported
            failed += 1
            failures.append((row.get("name"), str(error)[:70]))
            # A wrong logo is worse than none, but a channel that had artwork
            # should not lose it because a host blinked once.
            old = existing("ch%04d" % row["id"]) if isinstance(row.get("id"), int) else None
            if old:
                row["logo"] = BASE + old
                kept_old.append(row.get("name"))
            else:
                row["logo"] = ""
            continue

        name = stem + ext
        with open(os.path.join(LOGO_DIR, name), "wb") as handle:
            handle.write(body)
        row["logo"] = BASE + name
        mapping[name] = {"id": row.get("id"), "name": row.get("name"), "source": source}
        downloaded += 1

    if not report_only:
        used = {str(row.get("logo", "")).rsplit("/", 1)[-1] for row in catalog["channels"]}
        used.discard("")
        pruned = 0
        for name in os.listdir(LOGO_DIR):
            if name == "map.json" or name in used:
                continue
            os.remove(os.path.join(LOGO_DIR, name))
            pruned += 1
        mapping = {name: meta for name, meta in mapping.items() if name in used}

        catalog["note"] = "Logos are served from this repository under /logos."
        with io.open(CATALOG, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(catalog, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        with io.open(MAP, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(dict(sorted(mapping.items())), handle, ensure_ascii=False, indent=1)
            handle.write("\n")
        print("pruned %d unused files" % pruned)

    print("downloaded %d, already correct %d, failed %d" % (downloaded, reused, failed))
    print("without artwork:", sum(1 for row in catalog["channels"] if not row.get("logo")))
    for name, reason in failures[:15]:
        print("  %s: %s" % (name, reason))
    if kept_old:
        print("kept an unverified old file for %d: %s" % (len(kept_old), ", ".join(kept_old[:8])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
