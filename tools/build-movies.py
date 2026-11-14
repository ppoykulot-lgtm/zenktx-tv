#!/usr/bin/env python3
"""Builds the published Movies & Series catalogue from TMDB.

The three apps read an index and then page through titles as the reader
scrolls: no API key ships in any build, the poster and backdrop bases are
resolved here, and the player chain is written down once so the shells only
have to template a title id into a URL.

Output:
    docs/movies/index.json          servers, counts and the chunk list
    docs/movies/movies-01.json ...  CHUNK titles each
    docs/movies/series-01.json ...

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
OUT_DIR = os.path.join(ROOT, "docs", "movies")
KEY_FILE = os.path.join(ROOT, "tools", "tmdb.key")

API = "https://api.themoviedb.org/3"
POSTER = "https://image.tmdb.org/t/p/w500"
BACKDROP = "https://image.tmdb.org/t/p/w780"
UA = "ZENKTX-catalogue/1.0"

# A chunk is what one scroll costs to fetch: about a dozen screens of posters.
CHUNK = 150

# TMDB stops serving a list past page 500; sixty pages is twelve hundred titles
# per feed, which is as deep as the lists stay useful before they turn into
# straight-to-video filler.
FEEDS = [
    ("trending/{kind}/week", {}, 5),
    ("{kind}/popular", {}, 60),
    ("{kind}/top_rated", {}, 60),
    ("discover/{kind}", {"sort_by": "{sort}", "vote_count.gte": 10}, 60),
    ("discover/{kind}", {"sort_by": "popularity.desc", "vote_count.gte": 200}, 60),
]


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
    date_field = "primary_release_date" if kind == "movie" else "first_air_date"
    sort = f"{date_field}.desc"
    rows = []
    for raw_path, raw_params, page_count in FEEDS:
        path = "/" + raw_path.format(kind=kind)
        params = {str(k).format(sort=sort): str(v).format(sort=sort) for k, v in raw_params.items()}
        empty_pages = 0
        for page in range(1, page_count + 1):
            try:
                data = get(path, page=page, **params)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
                print(f"  skip {path} p{page}: {e}")
                break
            results = data.get("results", [])
            if not results:
                empty_pages += 1
                if empty_pages >= 2:
                    break
                continue
            for item in results:
                card = pick(item, names, kind)
                if not card or card["id"] in seen:
                    continue
                seen.add(card["id"])
                rows.append(card)
        print(f"  {path}{'' if not params else ' ' + str(params)} -> {len(rows)} total")
    return rows


SERVERS = [
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
]


def write_chunks(kind, titles):
    """Old chunks are cleared first so a shorter run cannot leave orphans."""
    for name in os.listdir(OUT_DIR):
        if name.startswith(f"{kind}-") and name.endswith(".json"):
            os.remove(os.path.join(OUT_DIR, name))
    files = []
    for index in range(0, len(titles), CHUNK):
        name = f"{kind}-{index // CHUNK + 1:02d}.json"
        with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as f:
            json.dump({"items": titles[index:index + CHUNK]}, f, ensure_ascii=False,
                      separators=(",", ":"))
            f.write("\n")
        files.append(name)
    return files


def main():
    seen = set()
    movie_genres = genres("movie")
    tv_genres = genres("tv")
    movies = collect("movie", movie_genres, seen)
    series = collect("tv", tv_genres, seen)
    if not movies or not series:
        sys.exit("TMDB returned nothing usable; keeping the published files")

    os.makedirs(OUT_DIR, exist_ok=True)
    movie_files = write_chunks("movies", movies)
    series_files = write_chunks("series", series)

    index = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Metadata from TMDB. Poster and backdrop URLs resolve without a key; the shells hold none.",
        "posterBase": POSTER,
        "backdropBase": BACKDROP,
        "chunkSize": CHUNK,
        "servers": SERVERS,
        "movies": {"count": len(movies), "files": movie_files},
        "series": {"count": len(series), "files": series_files},
    }
    with open(os.path.join(OUT_DIR, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
        f.write("\n")

    # The single-file catalogue the first builds used; the apps read index.json now.
    legacy = os.path.join(ROOT, "docs", "movies.json")
    if os.path.exists(legacy):
        os.remove(legacy)
        print("removed docs/movies.json (replaced by docs/movies/index.json)")

    total = sum(os.path.getsize(os.path.join(OUT_DIR, n))
                for n in os.listdir(OUT_DIR) if n.endswith(".json"))
    print(f"wrote {OUT_DIR}: {len(movies)} movies in {len(movie_files)} chunks, "
          f"{len(series)} series in {len(series_files)} chunks, {total // 1024} KB total")


if __name__ == "__main__":
    main()
