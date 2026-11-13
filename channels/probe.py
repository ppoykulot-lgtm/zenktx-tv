"""Probes every stream in the live feed and classifies it.

Outputs channels/probe.json with, per URL: HTTP status, whether the manifest is
HLS or DASH, whether it is encrypted at all, and which DRM systems it offers.
That tells us which MPDs a Windows/Electron build can never play (Widevine or
PlayReady only) versus the ones that really are ClearKey or unencrypted.
"""

import concurrent.futures
import json
import re
import urllib.request

FEED = ('https://iyadtv.pages.dev')
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/124.0 Safari/537.36')
WIDEVINE = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'
PLAYREADY = '9a04f079-9840-4286-ab92-e65be0885f95'
CLEARKEY = 'e2719d58-a985-b3c9-781a-b030af78d30e'


def get(url, timeout=20):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': '*/*'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.status, response.read(400000)


def probe(item):
    url = item.get('url') or ''
    out = {'id': item.get('id'), 'name': item.get('name'), 'url': url,
           'enabled': item.get('enabled') is not False,
           'declared': item.get('streamType') or '',
           'keyId': (item.get('keyId') or '').lower(),
           'key': item.get('key') or ''}
    try:
        status, body = get(url)
    except Exception as error:  # HTTPError carries a status; everything else does not
        status = getattr(error, 'code', 0) or 0
        out['status'] = status
        out['error'] = str(error)[:120]
        out['kind'] = 'dead'
        return out

    text = body[:200000].decode('utf-8', 'ignore')
    out['status'] = status
    if '<MPD' in text[:4000] or text.lstrip().startswith('<?xml'):
        out['type'] = 'dash'
        out['encrypted'] = 'ContentProtection' in text
        systems = []
        if WIDEVINE in text:
            systems.append('widevine')
        if PLAYREADY in text:
            systems.append('playready')
        if CLEARKEY in text:
            systems.append('clearkey')
        out['systems'] = systems
        kids = re.findall(r'default_KID="([^"]+)"', text)
        out['kids'] = sorted({k.lower() for k in kids})
        out['keyid_matches'] = bool(out['keyId']) and any(
            out['keyId'].replace('-', '') in k.replace('-', '') for k in out['kids'])
    elif text.startswith('#EXTM3U'):
        out['type'] = 'hls'
        out['encrypted'] = '#EXT-X-KEY' in text
    else:
        out['type'] = 'binary'
    out['kind'] = 'ok'
    return out


items = json.loads(get(FEED)[1].decode('utf-8'))
if isinstance(items, dict):
    items = list(items.values())
items = [i for i in items if isinstance(i, dict) and i.get('url')]
print('feed items:', len(items))

# Kept beside the probe so build-catalog.py can rebuild docs/catalog.json
# without another round trip. Both files are ignored by git.
with open('channels/feed.json', 'w', encoding='utf-8') as handle:
    json.dump(items, handle, ensure_ascii=False)

with concurrent.futures.ThreadPoolExecutor(max_workers=40) as pool:
    results = list(pool.map(probe, items))

summary = {}
for row in results:
    key = (row.get('kind'), row.get('type', ''), ','.join(row.get('systems', [])))
    summary[key] = summary.get(key, 0) + 1

with open('channels/probe.json', 'w', encoding='utf-8') as handle:
    json.dump({'feed': FEED, 'probed_at': __import__('datetime').datetime.utcnow().isoformat(),
               'items': results}, handle, ensure_ascii=False, indent=1)

print('--- summary (kind, type, drm) ---')
for key in sorted(summary, key=lambda k: -summary[k]):
    print(summary[key], key)
print('dead:', sum(1 for r in results if r.get('kind') == 'dead'))
print('dash clearkey-ready:', sum(1 for r in results
                                   if r.get('type') == 'dash' and 'clearkey' in (r.get('systems') or [])))
print('dash widevine/playready only:', sum(
    1 for r in results if r.get('type') == 'dash' and r.get('encrypted')
    and not r.get('systems')))
