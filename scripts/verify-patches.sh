#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../SillyTavern"
grep -q "ST_SKIP_WEBPACK" src/server-main.js
grep -q "ST_SKIP_WEBPACK" src/middleware/webpack-serve.js
grep -q "lib.bundle.js" src/middleware/webpack-serve.js
grep -q "ST_MOBILE" src/endpoints/extensions.js
test "$(grep -c 'blockOnMobile(response)' src/endpoints/extensions.js)" -ge 3
python3 - <<'PY'
import json, re
for name in ('package.json', 'package-lock.json'):
    d = json.load(open(name))
    if name == 'package-lock.json':
        d = d['packages']['']
    engines = d['engines']['node']
    m = re.search(r'\d+', engines)
    assert m, engines
    assert int(m.group()) <= 18, engines
PY
node --check src/server-main.js
node --check src/endpoints/extensions.js
node --check src/middleware/webpack-serve.js
echo "patches OK"
