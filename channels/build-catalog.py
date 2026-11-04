"""Builds docs/catalog.json — the one list every platform reads.

Input is channels/probe.json (the live feed plus what each address actually
returned). The catalogue is what the apps fetch on launch, so editing it is the
whole publishing flow: no APK, MSI or reinstall is involved.

Rules:
  * a channel whose manifest answered 4xx/5xx is published disabled, so nobody
    taps a link we already know is gone;
  * a channel whose manifest is Widevine/PlayReady is published with
    drmType "widevine" and a note, because no build in this project holds a
    licence for it;
  * everything else keeps its current address, logo, tags and order.
"""

import json
import datetime

PROBE = 'channels/probe.json'
OUT = 'docs/catalog.json'

CATEGORY_TAGS = [
    ('News', ('news',)),
    ('Sports', ('sports', 'pba', 'nba')),
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


def category(tags):
    lowered = [str(tag).lower() for tag in tags]
    for name, needles in CATEGORY_TAGS:
        if any(needle in lowered for needle in needles):
            return name
    return 'Entertainment'


def is_filipino(name, tags, group):
    hay = ' '.join([name, group] + [str(tag) for tag in tags]).lower()
    return any(hint in hay for hint in FILIPINO_HINTS)


with open(PROBE, encoding='utf-8') as handle:
    probe = json.load(handle)
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
    systems = row.get('systems') or []
    widevine = 'widevine' in systems or 'playready' in systems
    clearkey = 'clearkey' in systems

    channels.append({
        'id': row.get('id'),
        'order': order,
        'name': name,
        'category': category(tags),
        'tags': tags,
        'logo': source.get('logo') or '',
        'url': row.get('url') or '',
        'streamType': 'mpd' if row.get('type') == 'dash' else 'hls',
        'quality': source.get('quality') or 'HD',
        'isFilipino': is_filipino(name, tags, group),
        'enabled': not dead,
        'drmType': 'clearkey' if clearkey else ('widevine' if widevine else ''),
        'keyId': (row.get('keyId') or '') if clearkey else '',
        'key': (row.get('key') or '') if clearkey else '',
        'note': (
            'The link answered %s when this catalogue was built.' % row.get('status')
            if dead else
            'Widevine/PlayReady: needs a licence server, so no ZENKTX build can open it.' if widevine else ''
        ),
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
print('widevine:', sum(1 for c in channels if c['drmType'] == 'widevine'))
print('clearkey:', sum(1 for c in channels if c['drmType'] == 'clearkey'))
