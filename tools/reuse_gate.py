#!/usr/bin/env python3
"""RT-0 reuse gate: checks docs/reuse-manifest.tsv against the tree. Usage: python3 -I tools/reuse_gate.py [--root DIR] [--manifest FILE]
Per manifest row: the Iroh file starts with an SPDX line, a Copyright line and a "Derived from"/"Modified by Iroh contributors"
line (first 40 lines); NOTICE names the Iroh path; gate columns are all 'pass'. Excluded identifiers (third-party snippets we
decided not to take) must not appear in any source file. Last line: '# reuse_gate PASS' or '# reuse_gate FAIL n'."""
import argparse, os, re, sys
EXCLUDED = ['rrSpecularAlbedo', 'hash13']
COLS = ['source_repo', 'commit', 'path', 'licence', 'gate1', 'gate2', 'gate3', 'gate4', 'iroh_path', 'modification']
SCAN_EXT = ('.java', '.glsl', '.comp', '.vert', '.frag', '.slang', '.h', '.cpp', '.c', '.rs', '.py')
SCAN_SKIP = {'reference', 'build', '.git', '.gradle', 'run', 'natives-nv', 'nrd-src', 'reuse-gate-fixtures', 'build-keep'}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
    ap.add_argument('--manifest', default=None)
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    man = a.manifest or os.path.join(root, 'docs', 'reuse-manifest.tsv')
    errs = []
    notice = open(os.path.join(root, 'NOTICE'), encoding='utf-8', errors='replace').read() if os.path.exists(os.path.join(root, 'NOTICE')) else ''
    if not os.path.exists(man):
        errs.append('manifest missing: ' + man)
        rows = []
    else:
        lines = [l.rstrip('\n') for l in open(man, encoding='utf-8') if l.strip() and not l.startswith('#')]
        if not lines or lines[0].split('\t') != COLS:
            errs.append('manifest header must be: ' + '\t'.join(COLS))
            lines = [None]
        rows = [dict(zip(COLS, l.split('\t'))) for l in lines[1:]]
    for r in rows:
        ip = r.get('iroh_path', '')
        if len(r) != len(COLS) or not all(r.values()):
            errs.append('row incomplete: ' + ip); continue
        for g in ('gate1', 'gate2', 'gate3', 'gate4'):
            if r[g] != 'pass': errs.append(f'{ip}: {g} is {r[g]!r}, not pass')
        f = os.path.join(root, ip)
        if not os.path.isfile(f): errs.append(f'{ip}: file missing'); continue
        head = ''.join(open(f, encoding='utf-8', errors='replace').readlines()[:40])
        if not re.search(r'SPDX-License-Identifier:', head): errs.append(f'{ip}: no SPDX line in header')
        if not re.search(r'Copyright', head): errs.append(f'{ip}: no upstream copyright line in header')
        if not re.search(r'Derived from .+|Modified by Iroh contributors', head): errs.append(f'{ip}: no "Derived from"/"Modified by Iroh contributors" line')
        if ip not in notice: errs.append(f'{ip}: not named in NOTICE')
    for d, dn, fn in os.walk(root):
        dn[:] = [x for x in dn if x not in SCAN_SKIP]
        for n in fn:
            if n.endswith(SCAN_EXT) and n != 'reuse_gate.py':
                t = open(os.path.join(d, n), encoding='utf-8', errors='replace').read()
                for x in EXCLUDED:
                    if re.search(r'\b' + x + r'\b', t): errs.append(f'{os.path.relpath(os.path.join(d, n), root)}: excluded identifier {x}')
    for e in errs: print('FAIL:', e)
    print('# reuse_gate PASS' if not errs else f'# reuse_gate FAIL {len(errs)}')
    sys.exit(1 if errs else 0)
main()
