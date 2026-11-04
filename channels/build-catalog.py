"""Builds docs/catalog.json — the one list every platform reads.

Input is channels/probe.json (the live feed plus what each address actually
returned) and channels/keycheck.json (the manifest's default_KID compared with
the key id the feed ships). The catalogue is what the apps fetch on launch, so
editing it is the whole publishing flow: no APK, MSI or reinstall is involved.

Rules:
  * a channel whose manifest answered 4xx/5xx is published disabled, so nobody
    taps a link we already know is gone;
  * a channel whose key id equals the manifest's default_KID is ClearKey and
    carries its key: Chromium and Media3 can both decrypt that with no licence
    server, which is how the other IPTV apps play the same links;
  * an encrypted channel whose key does not match the manifest is Widevine or
    PlayReady and cannot be opened without a licence, so it says that instead;
  * an NBA stream gets its own category, because the feed publishes game
    streams that come and go daily.

The earlier build trusted the ContentProtection list, where almost every Cignal
and ABS-CBN manifest advertises Widevine and PlayReady only, so 145 playable
ClearKey channels were published as unplayable. Only the key match is evidence.
"""

import json
import datetime

PROBE = 'channels/probe.json'
KEYCHECK = 'channels/keycheck.json'
OUT = 'docs/catalog.json'

CATEGORY_TAGS = [
    ('News', ('news',)),
    ('Sports', ('sports', 'pba')),
    ('Movies', ('movies', 'films', 'cinema')),
    ('Kids', ('kids', 'children', 'cartoons')),
    ('Anime', ('anime', 'otaku')),
    ('Documentary', ('documentary', 'nature', 'wildlife', 'science')),
    ('Music', ('music',)),
]

FILIPINO_HINTS = (
    'philippin', 'pinoy', 'filipino', 'manila', 'abs-cbn', 'gma', 'tv5', 'a2z',
    'kapatid', 'kapamilya', 'ptv', 'smni', 'net25', 'one ph', 'pba', 'untv',
    'deped', 'iwant', 'gtv', 'alltv', 'bilyonaryo',
)


def category(name, tags):
    # The feed fills up with "8:30am Pistons vs Hornets" rows during a game and
    # empties again afterwards, so they need their own shelf instead of being
    # scattered through Sports.
    if 'nba' in str(name).lower():
        return 'NBA Live'
    lowered = [str(tag).lower() for tag in tags]
    for label, needles in CATEGORY_TAGS:
        if any(needle in lowered for needle in needles):
            return label
    return 'Entertainment'


def is_filipino(name, tags, group):
    hay = ' '.join([name, group] + [str(tag) for tag in tags]).lower()
    return any(hint in hay for hint in FILIPINO_HINTS)


with open(PROBE, encoding='utf-8') as handle:
    probe = json.load(handle)

with open(KEYCHECK, encoding='utf-8') as handle:
    # name -> {"kid_match": bool, ...}; names repeat in the feed, so the first
    # row for a name wins.
    keycheck = {}
    for row in json.load(handle):
        keycheck.setdefault(row.get('name'), row)


def as_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


with open('channels/feed.json', encoding='utf-8') as handle:
    # Ids arrive as numbers in one feed and as strings in another.
    feed = {str(item.get('id')): item for item in json.load(handle)}

channels = []
for order, row in enumerate(sorted(probe['items'], key=lambda r: as_int(r.get('id'))), start=1):
    source = feed.get(str(row.get('id')), {})
    tags = source.get('tags') or []
    group = source.get('group') or 'General'
    name = row.get('name') or source.get('name') or 'Channel'

    dead = row.get('kind') == 'dead'
    check = keycheck.get(name) or {}
    key_id = row.get('keyId') or source.get('keyId') or ''
    drm_key = row.get('key') or source.get('key') or ''

    # A key the manifest actually names is the only proof of ClearKey. The
    # ContentProtection list is not: these manifests advertise Widevine and
    # PlayReady while shipping a working ClearKey pair.
    clearkey = bool(check.get('kid_match')) and bool(key_id) and bool(drm_key)
    systems = row.get('systems') or []
    encrypted = row.get('encrypted') or 'ContentProtection' in str(row.get('drm') or '')
    widevine = not clearkey and bool(encrypted or systems)

    note = ''
    if dead:
        note = 'The link answered %s when this catalogue was built.' % row.get('status')
    elif clearkey:
        note = 'ClearKey: the key below decrypts this stream, so it plays in ZENKTX.'
    elif widevine:
        note = 'Widevine/PlayReady: needs a licence server, so no ZENKTX build can open it.'

    channels.append({
        'id': row.get('id'),
        'order': order,
        'name': name,
        'category': category(name, tags),
        'tags': tags,
        'logo': source.get('logo') or '',
        'url': row.get('url') or '',
        'streamType': 'mpd' if row.get('type') == 'dash' else 'hls',
        'quality': source.get('quality') or 'HD',
        'isFilipino': is_filipino(name, tags, group),
        'enabled': not dead,
        'drmType': 'clearkey' if clearkey else ('widevine' if widevine else ''),
        'keyId': key_id if clearkey else '',
        'key': drm_key if clearkey else '',
        'note': note,
    })

catalog = {
    'version': 1,
    'updatedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
    'source': probe.get('feed'),
    'note': ('This file is the channel list every ZENKTX build reads on launch. '
             'Edit it with admin.html or by hand and commit; the apps pick it up '
             'within a few minutes and never need a reinstall.'),
    'channels': channels,
}

with open(OUT, 'w', encoding='utf-8') as handle:
    json.dump(catalog, handle, ensure_ascii=False, indent=1)

print('channels:', len(channels))
print('enabled:', sum(1 for c in channels if c['enabled']))
print('clearkey:', sum(1 for c in channels if c['drmType'] == 'clearkey'))
print('widevine:', sum(1 for c in channels if c['drmType'] == 'widevine'))
print('nba live:', sum(1 for c in channels if c['category'] == 'NBA Live'))
