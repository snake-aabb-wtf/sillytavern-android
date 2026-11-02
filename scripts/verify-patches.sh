#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../SillyTavern"
grep -q "ST_SKIP_WEBPACK" src/server-main.js
grep -q "ST_MOBILE" src/endpoints/extensions.js
python3 - <<'PY'
import json
d=json.load(open('package.json'))
assert '18' in d['engines']['node'], d['engines']
PY
node --check src/server-main.js
node --check src/endpoints/extensions.js
echo "patches OK"
