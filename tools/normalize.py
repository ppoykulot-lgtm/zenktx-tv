import json, re, sys, unicodedata

SRC = r'C:\Users\Xpags\Desktop\_dx\channels_raw.json'
OUT_JSON = 'data/channels.json'
OUT_M3U = 'data/channels.m3u'

RULES = [
    ('Sports', {'sports', 'basketball', 'nba', 'ufc', 'mma', 'boxing', 'fighting', 'football', 'volleyball', 'sports'}),
    ('News', {'news', 'abs cbn', 'gma', 'public affairs'}),
    ('Movies', {'movies', 'cinema', 'film', 'movie'}),
    ('Kids', {'kids', 'cartoons', 'children', 'animation', 'anime', 'family'}),
    ('Music', {'music', 'radio'}),
    ('Documentary', {'documentary', 'science', 'nature', 'travel'}),
    ('International', {'international', 'china'}),
]

def classify(ch):
    tags = [str(t).lower() for t in (ch.get('tags') or [])]
    tagset = set(tags)
    for cat, keys in RULES:
        if tagset & keys:
            return cat
    return 'Entertainment'

def slug(s):
    s = unicodedata.normalize('NFKD', s)
    s = re.sub(r'[^a-zA-Z0-9]+', '-', s).strip('-').lower()
    return s or 'channel'

raw = json.load(open(SRC, encoding='utf8'))
out, seen = [], set()
for ch in raw:
    if not ch.get('enabled', True):
        continue
    url = (ch.get('url') or '').strip()
    name = (ch.get('name') or '').strip()
    if not url or not name:
        continue
    key = url
    if key in seen:
        continue
    seen.add(key)
    tags = [str(t) for t in (ch.get('tags') or []) if str(t).strip()]
    tags_l = {t.lower() for t in tags}
    out.append({
        'id': ch.get('id') or len(out) + 1,
        'name': name,
        'slug': slug(name),
        'category': classify(ch),
        'tags': list(dict.fromkeys(tags)),
        'logo': ch.get('logo') or '',
        'url': url,
        'streamType': (ch.get('streamType') or 'hls').lower(),
        'quality': ch.get('quality') or 'HD',
        'isFilipino': bool(tags_l & {'philippines', 'pinoy', 'local', 'abs cbn', 'gma'}),
    })

out.sort(key=lambda c: (c['category'], c['name'].lower()))
payload = {
    'source': 'dcoderztv-windows-1.0.0-setup.exe',
    'generated': '2026-11-01',
    'count': len(out),
    'channels': out,
}
json.dump(payload, open(OUT_JSON, 'w', encoding='utf8'), ensure_ascii=False, indent=1)

lines = ['#EXTM3U']
for c in out:
    logo = f' tvg-logo="{c["logo"]}"' if c['logo'] else ''
    lines.append(f'#EXTINF:-1 tvg-id="{c["slug"]}" tvg-name="{c["name"]}"{logo} group-title="{c["category"]}",{c["name"]}')
    lines.append(c['url'])
open(OUT_M3U, 'w', encoding='utf8', newline='\n').write('\n'.join(lines) + '\n')

from collections import Counter
print('channels:', len(out))
print(Counter(c['category'] for c in out).most_common())
print('filipino:', sum(1 for c in out if c['isFilipino']))
