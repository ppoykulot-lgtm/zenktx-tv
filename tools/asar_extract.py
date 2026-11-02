import json, os, struct, sys

def load(path):
    f = open(path, 'rb')
    head = f.read(16)
    f.seek(16)
    raw = f.read(400000).decode('utf8', errors='replace')
    js, end = json.JSONDecoder().raw_decode(raw)
    return js, 16 + end, path

def walk(node, prefix, out):
    for k, v in node.get('files', {}).items():
        p = prefix + k
        if 'files' in v:
            out.append((p, None))
            walk(v, p + '/', out)
        else:
            out.append((p, v))

def main():
    asar, dest = sys.argv[1], sys.argv[2]
    js, data_start, _ = load(asar)
    entries = []
    walk(js, '', entries)
    f = open(asar, 'rb')
    for p, meta in entries:
        target = os.path.join(dest, p.replace('/', os.sep))
        if meta is None:
            os.makedirs(target, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if 'link' in meta or 'offset' not in meta or 'size' not in meta:
            continue
        f.seek(data_start + int(meta['offset']))
        with open(target, 'wb') as o:
            o.write(f.read(int(meta['size'])))

main()
