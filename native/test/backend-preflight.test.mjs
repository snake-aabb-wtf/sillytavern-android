/**
 * Fail-fast test for the backend preflight.
 *
 * The seeded `settings.json` uses `main_api: "koboldhorde"`. Generate() then never
 * POSTs to /api/backends/chat-completions/generate, so a fresh install's first
 * message used to sit for the builder's full 180 s timeout and then return 500.
 * `assertSupportedBackend()` runs before any UI interaction and must reject the
 * seed immediately with an actionable message.
 *
 * Run from `native/`: `node --test test/backend-preflight.test.mjs`.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { assertSupportedBackend } from '../prompt-builder/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KOBOLD_SETTINGS = path.join(HERE, '../../.github/ci/fixtures/settings-kobold.json');

test('the koboldhorde seed is rejected with the backend name and the fix', () => {
    const settings = JSON.parse(fs.readFileSync(KOBOLD_SETTINGS, 'utf8'));
    assert.equal(settings.main_api, 'koboldhorde', 'the fixture must pin the seeded default');

    assert.throws(
        () => assertSupportedBackend(settings.main_api),
        error => {
            assert.match(error.message, /koboldhorde/);
            assert.match(error.message, /设置/);
            assert.match(error.message, /save a provider/);
            assert.match(error.message, /OpenAI/);
            return true;
        },
    );
});

test('a stale client snapshot points at a restart, not another save', () => {
    // Reached only when settings.json already selects openai but the running
    // client still holds its boot-time value: saving again cannot clear it.
    assert.throws(
        () => assertSupportedBackend('koboldhorde', { onDisk: 'openai' }),
        error => {
            assert.match(error.message, /restart the app/);
            assert.doesNotMatch(error.message, /save a provider/);
            return true;
        },
    );
});

test('openai is the supported backend', () => {
    assert.doesNotThrow(() => assertSupportedBackend('openai'));
});

test('an unset backend is rejected instead of starting a doomed send', () => {
    assert.throws(() => assertSupportedBackend(undefined), /unsupported backend/);
});
