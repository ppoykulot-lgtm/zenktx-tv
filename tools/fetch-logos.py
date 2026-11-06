"""Move the catalogue's logos onto our own Pages host.

The third-party logo hosts (iyadtv.pages.dev, iyad.click, wikia, plex) sit
behind Cloudflare bot rules: a browser gets the image, and the Android and
Windows clients get nothing at all. Downloading each logo once and serving it
from /docs makes every platform see the same picture, and the admin page keeps
editing the same rows.

Run from the repository root:

    python tools/fetch-logos.py            # download what is missing
    python tools/fetch-logos.py --report   # only report current state
"""

import hashlib
import io
import json
import os
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "docs", "catalog.json")
LOGO_DIR = os.path.join(ROOT, "docs", "logos")
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
        raise ValueError(f"not an image: {kind or 'unknown'}")
    if len(body) < 120:
        raise ValueError(f"too small: {len(body)} bytes")
    return body, KINDS[kind]


def main() -> int:
    report_only = "--report" in sys.argv
    with io.open(CATALOG, encoding="utf-8") as handle:
        catalog = json.load(handle)

    os.makedirs(LOGO_DIR, exist_ok=True)

    done = skipped = failed = 0
    failures = []

    for row in catalog["channels"]:
        logo = (row.get("logo") or "").strip()
        if not logo or logo.startswith(BASE):
            skipped += 1
            continue

        channel_id = row.get("id")
        # One file per row, named after the row so the mapping survives edits.
        stem = f"ch{channel_id:04d}" if isinstance(channel_id, int) else hashlib.md5(
            row.get("name", "").encode()
        ).hexdigest()[:8]

        if report_only:
            cached = [f for f in os.listdir(LOGO_DIR) if f.startswith(stem + ".")]
            if cached:
                skipped += 1
            else:
                failed += 1
                failures.append((row.get("name"), "missing"))
            continue

        # Resumable: a previous run may already hold this channel's file.
        existing = [f for f in os.listdir(LOGO_DIR) if f.startswith(stem + ".")]
        if existing:
            row["logo"] = BASE + existing[0]
            skipped += 1
            continue

        try:
            body, ext = fetch(logo)
        except Exception as error:  # noqa: BLE001 - report every host failure
            failed += 1
            failures.append((row.get("name"), str(error)[:80]))
            continue

        name = stem + ext
        with open(os.path.join(LOGO_DIR, name), "wb") as handle:
            handle.write(body)
        row["logo"] = BASE + name
        done += 1

    if not report_only:
        catalog["note"] = "Logos are served from this repository under /logos."
        with io.open(CATALOG, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(catalog, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

    print(f"downloaded {done}, already local {skipped}, failed {failed}")
    for name, reason in failures[:15]:
        print(f"  {name}: {reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
