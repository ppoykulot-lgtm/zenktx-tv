#!/usr/bin/env python3
"""Builds docs/movies.json from TMDB.

The three apps read one file: no API key ships in any build, the poster and
backdrop bases are resolved here, and the player chain is written down once so
the shells only have to template a title id into a URL.

Usage:  python tools/build-movies.py
"""
import json
import os
import sys
import urllib.parse
import urllib.request
import urllib.error
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "movies.json")
KEY_FILE = os.path.join(ROOT, "tools", "tmdb.key")

API = "https://api.themoviedb.org/3"
POSTER = "https://image.tmdb.org/t/p/w500"
BACKDROP = "https://image.tmdb.org/t/p/w780"
UA = "ZENKTX-catalogue/1.0"


def key():
    """The key comes from tools/tmdb.key, which is git-ignored."""
    if os.environ.get("TMDB_KEY"):
        return os.environ["TMDB_KEY"].strip()
    if os.path.exists(KEY_FILE):
        return open(KEY_FILE, encoding="utf-8").read().strip()
    sys.exit("no TMDB key: write it to tools/tmdb.key or set TMDB_KEY")


def get(path, **params):
    params["api_key"] = key()
    qs = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    req = urllib.request.Request(f"{API}{path}?{qs}", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def genres(kind):
    data = get(f"/genre/{kind}/list")
    return {g["id"]: g["name"] for g in data.get("genres", [])}


def pick(item, names, kind):
    """One card, or None when the row would render as a blank tile."""
    if not item.get("poster_path") or not item.get("overview"):
        return None
    if (item.get("vote_average") or 0) <= 0:
        return None
    date = item.get("release_date") or item.get("first_air_date") or ""
    title = item.get("title") or item.get("name") or ""
    if not title:
        return None
    return {
        "id": item["id"],
        "kind": kind,
        "title": title,
        "year": date[:4],
        "rating": round(float(item.get("vote_average") or 0), 1),
        "votes": item.get("vote_count") or 0,
        "overview": item["overview"],
        "poster": POSTER + item["poster_path"],
        "backdrop": BACKDROP + item["backdrop_path"] if item.get("backdrop_path") else "",
        "genres": [names[g] for g in item.get("genre_ids", []) if g in names][:3],
    }


def collect(kind, names, seen):
    """Trending first, then popular, so the newest titles lead the rows."""
    rows = []
    feeds = [
        (f"/trending/{kind}/week", {}),
        (f"/{kind}/popular", {}),
        (f"/discover/{kind}", {
            "sort_by": "primary_release_date.desc" if kind == "movie" else "first_air_date.desc",
            "vote_count.gte": 20,
        }),
    ]
    for path, params in feeds:
        try:
            data = get(path, **params)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
            print(f"  skip {path}: {e}")
            continue
        for item in data.get("results", []):
            card = pick(item, names, kind)
            if not card or card["id"] in seen:
                continue
            seen.add(card["id"])
            rows.append(card)
    return rows


def main():
    seen = set()
    movie_genres = genres("movie")
    tv_genres = genres("tv")
    movies = collect("movie", movie_genres, seen)
    series = collect("tv", tv_genres, seen)
    if not movies or not series:
        sys.exit("TMDB returned nothing usable; keeping the published file")

    doc = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Metadata from TMDB. Poster and backdrop URLs resolve without a key; the shells hold none.",
        "posterBase": POSTER,
        "backdropBase": BACKDROP,
        # Order is the fallback order in every app: a server that has gone dark is
        # skipped after the first timeout instead of shown as a black frame.
        "servers": [
            {"id": "vidlink", "name": "Player 1",
             "movie": "https://vidlink.pro/movie/{id}",
             "tv": "https://vidlink.pro/tv/{id}/{season}/{episode}"},
            {"id": "vidsrc", "name": "Player 2",
             "movie": "https://vidsrc.sh/embed/movie?tmdb={id}",
             "tv": "https://vidsrc.sh/embed/tv?tmdb={id}&season={season}&episode={episode}"},
            {"id": "vsembed", "name": "Player 3",
             "movie": "https://vsembed.ru/embed/movie?tmdb={id}",
             "tv": "https://vsembed.ru/embed/tv?tmdb={id}&season={season}&episode={episode}"},
            {"id": "vidzee", "name": "Player 4",
             "movie": "https://player.vidzee.wtf/embed/movie/{id}",
             "tv": "https://player.vidzee.wtf/embed/tv/{id}/{season}/{episode}"},
        ],
        "movies": movies[:90],
        "series": series[:90],
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {OUT}: {len(doc['movies'])} movies, {len(doc['series'])} series, "
          f"{len(doc['servers'])} servers, {os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    main()
