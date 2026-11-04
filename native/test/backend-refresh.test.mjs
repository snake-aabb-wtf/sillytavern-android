/**
 * Integration proof for the backend preflight's ordering.
 *
 * The preflight used to read `ctx().mainApi`, a boot-time snapshot. Saving a
 * provider rewrites `settings.json` but not that snapshot, so the message
 * "open 设置 and save a provider" was self-defeating: the user saved and got the
 * identical error until the app process restarted.
 *
 * This test exercises the real sequence against a real server + jsdom client:
 *
 *   1. boot with the seeded `main_api: koboldhorde` and assert the first build
 *      fails fast with the backend name (no 180 s send timeout);
 *   2. rewrite `settings.json` exactly as SettingsRepository.save posts it -
 *      the whole root with `main_api: "openai"`, which `/api/settings/save`
 *      writes wholesale (src/endpoints/settings.js:207-216);
 *   3. build again on the *same* builder and assert it assembles a prompt.
 *
 * Before the fix step 3 kept throwing `unsupported backend "koboldhorde"`, so
 * this test fails on the old code.
 *
 * Run from `native/`: `node --test test/backend-refresh.test.mjs`
 * (wired into native-parity.yml; it boots jsdom, so it is not part of test:unit).
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { createBuilder } from '../prompt-builder/index.mjs';
import { CHAT_FILE, USER_INPUT, getFreePort, prepareDataRoot } from './capture-reference.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const SERVER_DIR = process.env.NATIVE_SERVER_DIR
    ? path.resolve(process.env.NATIVE_SERVER_DIR)
    : path.join(REPO_ROOT, 'SillyTavern');

const FAIL_FAST_MS = 60_000;
const BUILD_TIMEOUT_MS = 180_000;

function withDeadline(promise, ms, label) {
    let timer;
    const deadline = new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} did not settle within ${ms}ms`)), ms);
    });
    return Promise.race([promise, deadline]).finally(() => clearTimeout(timer));
}

test('a provider saved after boot is used by the next build', { timeout: 8 * 60_000 }, async () => {
    const port = await getFreePort();
    const { tmpRoot, dataRoot } = prepareDataRoot({
        name: 'backend-refresh',
        card: 'character-plain.json',
        settings: 'settings-kobold.json',
        port,
    });
    const settingsPath = path.join(dataRoot, 'default-user', 'settings.json');
    assert.equal(JSON.parse(fs.readFileSync(settingsPath, 'utf8')).main_api, 'koboldhorde');

    let builder;
    try {
        builder = await createBuilder({
            dataDir: dataRoot,
            port,
            serverDir: SERVER_DIR,
            onLog: process.env.CAPTURE_VERBOSE ? line => process.stdout.write(`${line}\n`) : () => {},
        });

        const startedAt = Date.now();
        await assert.rejects(
            withDeadline(builder.build({
                avatarUrl: 'Fixture Plain.png',
                fileName: CHAT_FILE,
                userInput: USER_INPUT,
                timeoutMs: BUILD_TIMEOUT_MS,
            }), FAIL_FAST_MS, 'kobold build'),
            error => {
                assert.match(error.message, /unsupported backend/);
                assert.match(error.message, /koboldhorde/);
                return true;
            },
            'the seeded koboldhorde backend must be rejected before any send',
        );
        assert.ok(
            Date.now() - startedAt < FAIL_FAST_MS,
            'the preflight must fail fast, not wait for the send timeout',
        );

        // Exactly what SettingsRepository.save posts: the full settings root
        // with main_api replaced. The save endpoint writes the body wholesale.
        const root = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
        root.main_api = 'openai';
        fs.writeFileSync(settingsPath, JSON.stringify(root, null, 4));

        // Same builder, same app process: this is the "saved a provider" turn.
        const built = await withDeadline(builder.build({
            avatarUrl: 'Fixture Plain.png',
            fileName: CHAT_FILE,
            userInput: USER_INPUT,
            timeoutMs: BUILD_TIMEOUT_MS,
        }), BUILD_TIMEOUT_MS, 'openai build');

        assert.ok(Array.isArray(built.body?.messages), 'the second build returned no messages');
        assert.ok(
            JSON.stringify(built.body.messages).includes(USER_INPUT),
            'the second build lost the user input',
        );
        process.stdout.write(
            `PASS backend-refresh: koboldhorde rejected in ${Date.now() - startedAt}ms, ` +
            `then ${built.body.messages.length} prompt messages after saving openai\n`,
        );
    } finally {
        await builder?.close?.();
        fs.rmSync(tmpRoot, { recursive: true, force: true, maxRetries: 20, retryDelay: 250 });
    }
});
