#!/bin/bash
# RT-0: reuse_gate on the real tree and on the fixtures (ok must PASS, each bad_* must FAIL). Last line "# reuse_gate selftest PASS".
cd "$(dirname "$0")/.." || exit 2
rc=0; F=tools/reuse-gate-fixtures
python3 -I tools/reuse_gate.py >/dev/null || { echo "real tree FAIL"; rc=1; }
python3 -I tools/reuse_gate.py --root $F/ok --manifest $F/ok/manifest.tsv >/dev/null || { echo "ok fixture FAIL"; rc=1; }
for b in bad_header bad_notice bad_excluded; do
  python3 -I tools/reuse_gate.py --root $F/$b --manifest $F/$b/manifest.tsv >/dev/null && { echo "$b unexpectedly PASSED"; rc=1; }
done
[ $rc = 0 ] && echo "# reuse_gate selftest PASS" || echo "# reuse_gate selftest FAIL"; exit $rc
