#!/usr/bin/env bash
# Fails when an ST-ANDROID patch is missing *or* silently neutered.
#
# Syntax checks alone cannot catch the four regressions seen in review:
#   1. `const ST_MOBILE = true`  (guard always on, desktop behaviour lost)
#   2. webpack always skipped in server-main
#   3. inverted `/lib.js` gate in webpack-serve
#   4. deleted `if (!ST_MOBILE) return false;`
# The node block below therefore evaluates the patched code with stubbed
# `process.env`/`req`/`res` and asserts the desktop *and* mobile behaviour.
set -euo pipefail
cd "$(dirname "$0")/../SillyTavern"

node --check src/server-main.js
node --check src/endpoints/extensions.js
node --check src/middleware/webpack-serve.js

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

node - <<'JS'
'use strict';
const fs = require('node:fs');
const path = require('node:path');

function fail(msg) {
    console.error('verify-patches: ' + msg);
    process.exit(1);
}
function must(cond, msg) {
    if (!cond) fail(msg);
}

// --- shared extraction helpers --------------------------------------------
// Returns { cond, body } for the first `if (...)` after `marker`, with the
// condition text and the brace-matched body (without the outer braces).
function extractIf(src, marker) {
    const start = src.indexOf(marker);
    must(start >= 0, `marker not found: ${marker}`);
    return extractIfAt(src, src.indexOf('if (', start), marker);
}

function extractIfAt(src, start, label) {
    must(start >= 0, `no if-statement after ${label}`);
    let depth = 0;
    let condEnd = -1;
    for (let i = start + 3; i < src.length; i++) {
        if (src[i] === '(') depth++;
        else if (src[i] === ')') {
            depth--;
            if (depth === 0) { condEnd = i; break; }
        }
    }
    must(condEnd > 0, `unbalanced condition after ${label}`);
    let bodyStart = src.indexOf('{', condEnd);
    must(bodyStart > 0, `no body after ${label}`);
    let d = 0;
    let bodyEnd = -1;
    for (let i = bodyStart; i < src.length; i++) {
        if (src[i] === '{') d++;
        else if (src[i] === '}') {
            d--;
            if (d === 0) { bodyEnd = i; break; }
        }
    }
    must(bodyEnd > 0, `unbalanced body after ${label}`);
    // Include a trailing `else { ... }` so if/else patches are executed whole.
    let end = bodyEnd;
    const elseMatch = /^\s*else\b/.exec(src.slice(bodyEnd + 1, bodyEnd + 64));
    if (elseMatch) {
        const elseStart = bodyEnd + 1 + elseMatch[0].length;
        const elseBrace = src.indexOf('{', elseStart);
        let e = 0;
        for (let i = elseBrace; i < src.length; i++) {
            if (src[i] === '{') e++;
            else if (src[i] === '}') {
                e--;
                if (e === 0) { end = i; break; }
            }
        }
        must(end > bodyEnd, `unbalanced else body after ${label}`);
    }
    const cond = src.slice(start + 4, condEnd);
    return {
        cond,
        body: src.slice(bodyStart + 1, bodyEnd),
        statement: src.slice(start, end + 1),
    };
}

// --- extensions.js: ST_MOBILE guard + blockOnMobile ------------------------
const ext = fs.readFileSync('src/endpoints/extensions.js', 'utf8');

const declMatch = ext.match(/const ST_MOBILE = [^\n;]+;/);
must(declMatch, 'extensions.js: ST_MOBILE declaration missing');
const fnMatch = ext.match(/function blockOnMobile\(res\) \{[\s\S]*?\n\}/);
must(fnMatch, 'extensions.js: blockOnMobile() missing');

function mobileGuard(envValue) {
    const env = envValue === undefined ? {} : { ST_MOBILE: envValue };
    return new Function(
        'process',
        `${declMatch[0]}\n${fnMatch[0]}\nreturn blockOnMobile;`,
    )({ env });
}

const desktopCalls = [];
const desktopRes = {
    status(code) { desktopCalls.push(code); return { json() {} }; },
};
must(
    mobileGuard(undefined)(desktopRes) === false && desktopCalls.length === 0,
    'extensions.js: blockOnMobile must be a no-op when ST_MOBILE is unset ' +
        '(regression: const ST_MOBILE = true / deleted `if (!ST_MOBILE) return false`)',
);

const mobileCalls = [];
const mobileRes = {
    status(code) { mobileCalls.push(code); return { json() {} }; },
};
must(
    mobileGuard('1')(mobileRes) === true && mobileCalls[0] === 501,
    'extensions.js: blockOnMobile must answer 501 when ST_MOBILE=1',
);

for (const route of ['/update', '/branches', '/switch', '/version']) {
    const re = new RegExp(
        `router\\.post\\('${route}'[\\s\\S]{0,500}?blockOnMobile\\(response\\)`,
    );
    must(re.test(ext), `extensions.js: ${route} must call blockOnMobile(response)`);
}

// /install must keep the pure-JS git path.
const installBlock = ext.slice(ext.indexOf("router.post('/install'"));
const installEnd = installBlock.indexOf('router.post(', 10);
must(
    !installBlock.slice(0, installEnd > 0 ? installEnd : undefined).includes('blockOnMobile'),
    'extensions.js: /install must not be blocked on mobile',
);

// --- webpack-serve.js: /lib.js gate ---------------------------------------
const wp = fs.readFileSync('src/middleware/webpack-serve.js', 'utf8');
const gate = extractIf(wp, 'ST-ANDROID: the app ships a prebuilt bundle');
must(gate.body.includes('lib.bundle.js'), 'webpack-serve.js: gate must send lib.bundle.js');

function serve(envValue, method, reqPath) {
    const sent = [];
    const env = envValue === undefined ? {} : { ST_SKIP_WEBPACK: envValue };
    // eslint-disable-next-line no-new-func
    new Function(
        'process', 'req', 'res', 'path', 'serverDirectory',
        `if (${gate.cond}) { ${gate.body} }`,
    )(
        { env },
        { method, path: reqPath },
        { sendFile(file, opts) { sent.push({ file, opts }); } },
        path,
        '/srv-root',
    );
    return sent;
}

const servedMobile = serve('1', 'GET', '/lib.js');
must(
    servedMobile.length === 1 && servedMobile[0].file === 'lib.bundle.js',
    'webpack-serve.js: ST_SKIP_WEBPACK=1 must serve public/lib.bundle.js for GET /lib.js',
);
must(
    String(servedMobile[0].opts && servedMobile[0].opts.root).endsWith('public'),
    'webpack-serve.js: prebuilt lib.js must come from <serverDirectory>/public',
);
must(
    serve(undefined, 'GET', '/lib.js').length === 0,
    'webpack-serve.js: prebuilt lib.js must NOT be served when ST_SKIP_WEBPACK is unset ' +
        '(regression: inverted /lib.js gate)',
);
must(
    serve('1', 'GET', '/lib.css').length === 0,
    'webpack-serve.js: gate must only match /lib.js',
);
must(
    serve('1', 'POST', '/lib.js').length === 0,
    'webpack-serve.js: gate must only match GET /lib.js',
);

// --- server-main.js: webpack skip vs compile ------------------------------
const sm = fs.readFileSync('src/server-main.js', 'utf8');
const setup = extractIf(sm, '// Wait for frontend libs to compile');
must(
    setup.statement.includes('webpackMiddleware.runWebpackCompiler'),
    'server-main.js: setup must still compile webpack when not skipped ' +
        '(regression: webpack always skipped)',
);

async function runSetup(envValue) {
    const compiled = [];
    const logs = [];
    const env = envValue === undefined ? {} : { ST_SKIP_WEBPACK: envValue };
    const webpackMiddleware = {
        runWebpackCompiler(arg) { compiled.push(arg); return Promise.resolve(); },
    };
    const consoleStub = {
        log(...args) { logs.push(args.join(' ')); },
        warn() {}, error() {},
    };
    // eslint-disable-next-line no-new-func
    const run = new Function(
        'process', 'webpackMiddleware', 'console',
        `return (async () => { ${setup.statement} })();`,
    );
    await run({ env }, webpackMiddleware, consoleStub);
    return { compiled, logs };
}

(async () => {
    const skipped = await runSetup('1');
    must(
        skipped.compiled.length === 0,
        'server-main.js: ST_SKIP_WEBPACK=1 must skip webpack compilation',
    );
    must(
        skipped.logs.some(line => line.includes('prebuilt')),
        'server-main.js: ST_SKIP_WEBPACK=1 must log the prebuilt-libs path',
    );

    const compiledRun = await runSetup(undefined);
    must(
        compiledRun.compiled.length === 1,
        'server-main.js: webpack must be compiled exactly once when ST_SKIP_WEBPACK is unset ' +
            '(regression: webpack always skipped)',
    );

    console.log('patches OK');
})().catch(error => {
    console.error('verify-patches: ' + (error && error.stack ? error.stack : error));
    process.exit(1);
});
JS
