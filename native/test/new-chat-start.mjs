/**
 * Regression test: a chat that the native client just created must produce a
 * prompt, whatever its length.
 *
 * `NewChatService` writes exactly two shapes:
 *  - header + the character's greeting (one message), and
 *  - header only, when the card has no greeting at all.
 *
 * The prompt builder used to wait for `chat.length >= 2` ("fixture chat to
 * load"), which only holds for the parity fixtures' two-message history. On a
 * brand-new chat that guard never became true, so the first send sat for 60 s
 * and then failed with "fixture chat to load".
 *
 * Both cases are reproduced here against a real server + jsdom client, with no
 * provider contacted: `createBuilder` intercepts /generate with a canned reply.
 *
 * Run from `native/`: `node test/new-chat-start.mjs` (wired into native-parity.yml).
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { USER_INPUT, getFreePort, prepareDataRoot } from './capture-reference.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const SERVER_DIR = process.env.NATIVE_SERVER_DIR
    ? path.resolve(process.env.NATIVE_SERVER_DIR)
    : path.join(REPO_ROOT, 'SillyTavern');
const BUILDER_CLI = path.join(REPO_ROOT, 'native/prompt-builder/index.mjs');

// The old guard waits 60 s before failing; leave room for that to surface as a
// readable failure instead of a killed process with no output.
const CHILD_TIMEOUT_MS = 8 * 60_000;

const GREETING = 'Hello from a chat the app just created.';
const GREETING_FILE = 'new-chat-greeting';
const EMPTY_FILE = 'new-chat-empty';

const HEADER = {
    user_name: 'User',
    character_name: 'Fixture Plain',
    // The same ISO-8601 shape NewChatService writes.
    create_date: '2026-11-04T07:41:02Z',
    chat_metadata: { integrity: '2f4d8c1e-0000-4000-8000-000000000001' },
};

const GREETING_MESSAGE = {
    name: 'Fixture Plain',
    is_user: false,
    is_system: false,
    send_date: '2026-11-04T07:41:02Z',
    mes: GREETING,
    extra: {},
    swipes: [GREETING],
    swipe_id: 0,
    swipe_info: [],
};

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

function writeChat(directory, name, messages) {
    const lines = [JSON.stringify(HEADER), ...messages.map(message => JSON.stringify(message))];
    fs.writeFileSync(path.join(directory, `${name}.jsonl`), lines.join('\n'));
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

async function buildCase({ dataRoot, fileName, label, expectGreeting }) {
    const out = path.join(dataRoot, '..', `${label}-body.json`);
    const specPath = path.join(dataRoot, '..', `${label}-spec.json`);
    fs.writeFileSync(specPath, JSON.stringify({
        name: label,
        dataDir: dataRoot,
        port: await getFreePort(),
        serverDir: SERVER_DIR,
        avatarUrl: 'Fixture Plain.png',
        fileName,
        userInput: USER_INPUT,
        type: 'normal',
        out,
    }, null, 2));

    const startedAt = Date.now();
    const { code, stdout, stderr } = await runBuilder(specPath);
    if (code !== 0) {
        throw new Error(
            `${label}: builder exited with ${code}\n--- stdout ---\n${stdout}\n--- stderr ---\n${stderr.slice(-8000)}`,
        );
    }

    const body = JSON.parse(fs.readFileSync(out, 'utf8')).body;
    assert(Array.isArray(body.messages), `${label}: body.messages is not an array`);
    assert(body.messages.length > 0, `${label}: body has no messages`);
    const serialized = JSON.stringify(body.messages);
    assert(serialized.includes(USER_INPUT), `${label}: the user input is missing from the prompt`);
    if (expectGreeting) {
        assert(serialized.includes(GREETING), `${label}: the greeting is missing from the prompt`);
    }

    process.stdout.write(
        `PASS ${label}: ${body.messages.length} prompt messages, ${Date.now() - startedAt}ms\n`,
    );
    return body;
}

async function main() {
    const port = await getFreePort();
    const { tmpRoot, dataRoot } = prepareDataRoot({
        name: 'new-chat',
        card: 'character-plain.json',
        settings: 'settings-plain.json',
        port,
    });

    try {
        const characterChatsDir = path.join(dataRoot, 'default-user', 'chats', 'Fixture Plain');
        writeChat(characterChatsDir, GREETING_FILE, [GREETING_MESSAGE]);
        writeChat(characterChatsDir, EMPTY_FILE, []);

        // Header + one character message: the count guard never reaches 2.
        await buildCase({
            dataRoot,
            fileName: GREETING_FILE,
            label: 'new-chat-greeting',
            expectGreeting: true,
        });
        // Header only: the count guard never even reaches 1.
        await buildCase({
            dataRoot,
            fileName: EMPTY_FILE,
            label: 'new-chat-empty',
            expectGreeting: false,
        });
    } finally {
        // The builder's server can still be flushing files when close() returns;
        // a plain rmSync then fails with ENOTEMPTY and turns a PASS into a red job.
        fs.rmSync(tmpRoot, { recursive: true, force: true, maxRetries: 20, retryDelay: 250 });
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
