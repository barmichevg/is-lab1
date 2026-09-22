#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/is-lab1}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsS "$BASE_URL/api/organizations?page=0&size=2&sort=name&direction=asc" > "$TMP/page0.json"
curl -fsS "$BASE_URL/api/organizations?page=1&size=2&sort=name&direction=asc" > "$TMP/page1.json"
curl -fsS "$BASE_URL/api/reference/coordinates" > "$TMP/coordinates.json"
curl -fsS "$BASE_URL/api/reference/addresses" > "$TMP/addresses.json"
curl -fsS "$BASE_URL/api/reference/locations" > "$TMP/locations.json"

python3 - "$TMP" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
p0 = json.loads((root/'page0.json').read_text())
p1 = json.loads((root/'page1.json').read_text())
assert p0['page'] == 0 and p0['size'] == 2
assert p1['page'] == 1 and p1['size'] == 2
assert len(p0['items']) <= 2 and len(p1['items']) <= 2
names = [x['name'] for x in p0['items']]
assert names == sorted(names, key=lambda x: x.lower()), f'Page 0 is not sorted by name: {names}'
for fn in ('coordinates.json','addresses.json','locations.json'):
    value = json.loads((root/fn).read_text())
    assert isinstance(value, list), f'{fn} must contain a JSON array'
print(f"API OK. total organizations={p0['total']}, page0={len(p0['items'])}, page1={len(p1['items'])}")
PY
