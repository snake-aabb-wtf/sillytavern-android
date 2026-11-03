/**
 * Regression test for the prompt builder's readiness gate.
 *
 * The builder used to require `chat_completion_source === 'openai'` before it
 * reported ready (dom-host.mjs). That is the *default* source, so a user who
 * saved the built-in "OpenAI 兼容" (custom) preset and restarted would wait out
 * the 300 s readiness timeout and every chat turn would fail. The parity capture
 * only ever exercises `openai`, so this test boots the builder against a
 * custom-source data root and proves it reaches ready and produces a body.
 *
 * No provider is contacted: `createBuilder` intercepts /generate with a canned
 * response and hands the captured body back.
 *
 * Run from `native/`: `node test/custom-source.mjs` (wired into native-parity.yml).
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { CHAT_FILE, USER_INPUT, getFreePort, prepareDataRoot } from './capture-reference.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const SERVER_DIR = process.env.NATIVE_SERVER_DIR
    ? path.resolve(process.env.NATIVE_SERVER_DIR)
    : path.join(REPO_ROOT, 'SillyTavern');
const BUILDER_CLI = path.join(REPO_ROOT, 'native/prompt-builder/index.mjs');

// The old gate waits 300 s before failing; leave room for that to surface as a
// readable failure instead of a killed process with no output.
const CHILD_TIMEOUT_MS = 8 * 60_000;

const CASE = {
    name: 'custom-source',
    card: 'character-plain.json',
    settings: 'settings-custom.json',
    expectWorldInfo: false,
    variant: null,
};

function assertEqual(actual, expected, label) {
    if (actual !== expected) {
        throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}

function runBuilder(specPath) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.execPath, [BUILDER_CLI, '--spec', specPath], {
            cwd: REPO_ROOT,
            env: { ...process.env },
            stdio: ['ignore', 'pipe', 'pipe'],
        });
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', chunk => { stdout += String(chunk); });
        child.stderr.on('data', chunk => { stderr += String(chunk); });

        const timer = setTimeout(() => {
            child.kill('SIGKILL');
            reject(new Error(`builder timed out after ${CHILD_TIMEOUT_MS}ms\n${stderr.slice(-4000)}`));
        }, CHILD_TIMEOUT_MS);

        child.on('error', error => {
            clearTimeout(timer);
            reject(error);
        });
        child.on('exit', code => {
            clearTimeout(timer);
            resolve({ code, stdout, stderr });
        });
    });
}

async function main() {
    const port = await getFreePort();
    const { tmpRoot, dataRoot, avatarUrl } = prepareDataRoot({ ...CASE, port });

    try {
        // prepareDataRoot writes only the OpenAI key (the parity fixtures are
        // OpenAI). The custom source reads SECRET_KEYS.CUSTOM at request time.
        const secretsPath = path.join(dataRoot, 'default-user', 'secrets.json');
        const secrets = JSON.parse(fs.readFileSync(secretsPath, 'utf8'));
        secrets.api_key_custom = 'sk-fixture-not-used';
        fs.writeFileSync(secretsPath, JSON.stringify(secrets));

        const out = path.join(tmpRoot, 'custom-body.json');
        const specPath = path.join(tmpRoot, 'spec.json');
        fs.writeFileSync(specPath, JSON.stringify({
            name: CASE.name,
            dataDir: dataRoot,
            port,
            serverDir: SERVER_DIR,
            avatarUrl,
            fileName: CHAT_FILE,
            userInput: USER_INPUT,
            type: 'normal',
            out,
        }, null, 2));

        const startedAt = Date.now();
        const { code, stdout, stderr } = await runBuilder(specPath);
        if (code !== 0) {
            throw new Error(`builder exited with ${code}\n--- stdout ---\n${stdout}\n--- stderr ---\n${stderr.slice(-8000)}`);
        }

        const result = JSON.parse(fs.readFileSync(out, 'utf8'));
        const body = result.body;
        // Reaching this file at all means createDomHost's waitFor returned; with
        // the old gate it would have thrown after 300 s.
        assertEqual(body.chat_completion_source, 'custom', 'body.chat_completion_source');
        assertEqual(body.custom_url, 'https://api.example.com/v1', 'body.custom_url');
        assertEqual(body.model, 'fixture-custom-model', 'body.model');

        process.stdout.write(
            `PASS custom-source: ready + body (source=${body.chat_completion_source}, ` +
            `model=${body.model}, ${body.messages.length} messages, ${Date.now() - startedAt}ms)\n`,
        );
    } finally {
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
