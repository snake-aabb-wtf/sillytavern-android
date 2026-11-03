/**
 * Parity test: reproduce the captured /generate bodies headlessly.
 *
 * For every reference fixture produced by capture-reference.mjs, this test
 * boots the real client inside jsdom (in its own process, because the client's
 * module graph is a singleton), sends the same message, and compares the
 * resulting POST body field by field with the browser capture.
 *
 * Output: fixtures/headless/<name>.json
 * Run from `native/`: npm run test:headless
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { CASES, CHAT_FILE, USER_INPUT, FIXTURES_DIR, getFreePort, prepareDataRoot } from './capture-reference.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
// NATIVE_SERVER_DIR points the harness at a copy of the server tree (for
// example one pruned the way stage-runtime.sh prunes it) without touching the
// upstream SillyTavern/ checkout.
const SERVER_DIR = process.env.NATIVE_SERVER_DIR
    ? path.resolve(process.env.NATIVE_SERVER_DIR)
    : path.join(REPO_ROOT, 'SillyTavern');
const BUILDER_CLI = path.join(REPO_ROOT, 'native/prompt-builder/index.mjs');
const REFERENCE_DIR = path.join(REPO_ROOT, 'fixtures/reference');
const HEADLESS_DIR = path.join(REPO_ROOT, 'fixtures/headless');

const CHILD_TIMEOUT_MS = 15 * 60_000;

/**
 * Fields that legitimately differ between the browser capture and the headless
 * build. Empty on purpose: every captured body is deterministic, so anything
 * that differs is a real regression. Add an exact JSON path here only with a
 * one-line reason; never ignore a whole object or a "fields we do not know".
 */
const IGNORED_PATHS = new Set([]);

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));

function describe(value) {
    if (value === undefined) return '<missing>';
    return JSON.stringify(value);
}

/**
 * Returns the first differing path (e.g. body.messages[3].content) or null.
 */
function firstDifference(expected, actual, at = 'body') {
    if (IGNORED_PATHS.has(at)) return null;

    if (Array.isArray(expected) || Array.isArray(actual)) {
        if (!Array.isArray(expected) || !Array.isArray(actual)) {
            return { path: at, expected, actual };
        }
        if (expected.length !== actual.length) {
            return { path: `${at}.length`, expected: expected.length, actual: actual.length };
        }
        for (let index = 0; index < expected.length; index++) {
            const difference = firstDifference(expected[index], actual[index], `${at}[${index}]`);
            if (difference) return difference;
        }
        return null;
    }

    if (expected === null || actual === null) {
        return Object.is(expected, actual) ? null : { path: at, expected, actual };
    }

    if (typeof expected === 'object' || typeof actual === 'object') {
        if (typeof expected !== 'object' || typeof actual !== 'object') {
            return { path: at, expected, actual };
        }
        for (const key of Object.keys(expected)) {
            if (!(key in actual)) {
                return { path: `${at}.${key}`, expected: expected[key], actual: undefined };
            }
            const difference = firstDifference(expected[key], actual[key], `${at}.${key}`);
            if (difference) return difference;
        }
        for (const key of Object.keys(actual)) {
            if (!(key in expected)) {
                return { path: `${at}.${key}`, expected: undefined, actual: actual[key] };
            }
        }
        return null;
    }

    return Object.is(expected, actual) ? null : { path: at, expected, actual };
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
            reject(new Error(`Headless builder timed out after ${CHILD_TIMEOUT_MS}ms\n${stderr.slice(-4000)}`));
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

async function buildCase(caseSpec) {
    const port = await getFreePort();
    const { tmpRoot, dataRoot, avatarUrl } = prepareDataRoot({ ...caseSpec, port });

    const out = path.join(HEADLESS_DIR, `${caseSpec.name}.json`);
    const spec = {
        name: caseSpec.name,
        dataDir: dataRoot,
        port,
        serverDir: SERVER_DIR,
        avatarUrl,
        fileName: CHAT_FILE,
        userInput: USER_INPUT,
        type: 'normal',
        out,
    };
    const specPath = path.join(tmpRoot, 'headless-spec.json');
    fs.writeFileSync(specPath, JSON.stringify(spec, null, 2));

    const startedAt = Date.now();
    try {
        const { code, stdout, stderr } = await runBuilder(specPath);
        const elapsedMs = Date.now() - startedAt;
        fs.writeFileSync(
            out.replace(/\.json$/, '.log'),
            `exit=${code}\n--- stdout ---\n${stdout}\n--- stderr ---\n${stderr}\n`,
        );
        if (code !== 0) {
            process.stderr.write(`--- child stderr (tail) ---\n${stderr.slice(-12000)}\n`);
            throw new Error(`Headless builder exited with ${code} (full log: ${out.replace(/\.json$/, '.log')})`);
        }
        if (stdout.trim()) process.stdout.write(`  ${stdout.trim()}\n`);
        if (stderr.trim() && process.env.HEADLESS_VERBOSE === '1') process.stderr.write(stderr);
        return { out, elapsedMs };
    } finally {
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
}

async function main() {
    fs.mkdirSync(HEADLESS_DIR, { recursive: true });
    if (!fs.existsSync(REFERENCE_DIR)) {
        throw new Error(`No reference captures at ${REFERENCE_DIR}. Run "npm run capture:reference" first.`);
    }

    let failures = 0;
    let totalMs = 0;

    for (const caseSpec of CASES) {
        const referencePath = path.join(REFERENCE_DIR, `${caseSpec.name}.json`);
        if (!fs.existsSync(referencePath)) {
            process.stdout.write(`FAIL ${caseSpec.name}: missing reference ${referencePath}\n`);
            failures++;
            continue;
        }

        process.stdout.write(`\n=== ${caseSpec.name} ===\n`);
        const { out, elapsedMs } = await buildCase(caseSpec);
        totalMs += elapsedMs;

        const reference = readJson(referencePath);
        const headless = readJson(out);
        const difference = firstDifference(reference.body, headless.body);

        if (difference) {
            failures++;
            process.stdout.write(`FAIL ${caseSpec.name} at ${difference.path}\n`);
            process.stdout.write(`  expected: ${describe(difference.expected)}\n`);
            process.stdout.write(`  actual:   ${describe(difference.actual)}\n`);
            process.stdout.write(`  reference: ${referencePath}\n`);
            process.stdout.write(`  headless:  ${out}\n`);
            continue;
        }

        process.stdout.write(
            `PASS ${caseSpec.name}: body deep-equals (${headless.body.messages.length} messages, model=${headless.body.model}, ` +
            `${(elapsedMs / 1000).toFixed(1)}s wall clock)\n`,
        );
    }

    process.stdout.write(`\nTotal headless build time: ${(totalMs / 1000).toFixed(1)}s across ${CASES.length} fixtures\n`);

    if (failures > 0) {
        process.stdout.write(`RESULT: FAIL (${failures}/${CASES.length})\n`);
        process.exitCode = 1;
        return;
    }
    process.stdout.write(`RESULT: PASS (${CASES.length}/${CASES.length})\n`);
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
