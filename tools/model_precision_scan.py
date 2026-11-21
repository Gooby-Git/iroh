import sys, zipfile, json, glob, os, re
# Block models whose element coordinates are not multiples of the compressed terrain step (1/2048 block = 1/128 model unit).
def scan(jar):
    hits = []
    try:
        z = zipfile.ZipFile(jar)
    except Exception:
        return hits
    for n in z.namelist():
        if not re.match(r'assets/[^/]+/models/block/.+\.json$', n):
            continue
        try:
            m = json.loads(z.read(n).decode('utf-8', 'replace'))
        except Exception:
            continue
        els = m.get('elements') if isinstance(m, dict) else None
        if not isinstance(els, list):
            continue
        offgrid = []
        for e in els:
            for k in ('from', 'to'):
                for c in e.get(k, []) or []:
                    try:
                        v = float(c) * 128
                    except Exception:
                        continue
                    if abs(v - round(v)) > 1e-4:
                        offgrid.append(float(c))
        if offgrid:
            # near a block plane (within half a step: 0.0625 model units) = the collapse case
            near = [c for c in offgrid if min(c % 16, 16 - c % 16) < 16 / 2048 / 2]
            hits.append((n, len(offgrid), len(near), sorted(set(offgrid))[:4]))
    return hits

jars = sys.argv[1:]
total = 0
near_total = 0
for j in jars:
    h = scan(j)
    if not h:
        continue
    print('==', os.path.basename(j), len(h), 'models')
    for n, k, nr, ex in [x for x in h if x[2] > 0]:
        print('   ', n.split('/models/block/')[0].split('assets/')[1] + ':' + n.split('/models/block/')[1], 'offgrid', k, 'near-plane', nr, ex)
    total += len(h)
    near_total += sum(1 for x in h if x[2] > 0)
print('# models with off-grid coordinates:', total, '; with a coordinate within half a step of a block plane (the collapse case):', near_total)
