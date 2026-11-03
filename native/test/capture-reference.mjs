/**
 * Capture the exact POST bodies the real SillyTavern web UI sends to
 * /api/backends/chat-completions/generate.
 *
 * The script boots an unmodified SillyTavern server against a throwaway data
 * root, opens the real page in headless Chromium, selects a fixture character,
 * loads a fixture chat, sends one user message and records the intercepted
 * request body. Requests to /generate and the status probe are fulfilled by
 * Playwright, so no API key leaves the machine.
 *
 * Output: fixtures/reference/<name>.json = { url, body, ...fixture metadata }.
 * Run from `native/`: npm run capture:reference
 */
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { chromium } from 'playwright';
import { write as writeCharacterCard } from '../../SillyTavern/src/character-card-parser.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const SERVER_DIR = path.join(REPO_ROOT, 'SillyTavern');
export const FIXTURES_DIR = path.join(REPO_ROOT, '.github/ci/fixtures');
const OUTPUT_DIR = path.join(REPO_ROOT, 'fixtures/reference');

export const USER_INPUT = 'Are apples always red?';
export const CHAT_FILE = 'fixture-chat';
export const MODEL = 'gpt-4o-mini';
const SSE_BODY = 'data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n';

// 1x1 transparent PNG. The character card parser only needs a valid PNG to
// wrap the JSON metadata in; the image content is irrelevant.
const BASE_PNG = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64',
);

/**
 * Fixture matrix. `_variant` records the non-default settings a capture used so
 * Task 2 can reproduce the same request.
 */
export const CASES = [
    {
        name: 'character-plain',
        card: 'character-plain.json',
        settings: 'settings-plain.json',
        expectWorldInfo: false,
        variant: null,
    },
    {
        name: 'character-worldinfo',
        card: 'character-worldinfo.json',
        settings: 'settings-plain.json',
        expectWorldInfo: true,
        variant: null,
    },
    {
        name: 'preset-override',
        card: 'character-plain.json',
        settings: 'settings-preset.json',
        expectWorldInfo: false,
        variant: { temp_openai: 0.42, top_p_openai: 0.85 },
    },
];

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function getFreePort() {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    const { port } = server.address();
    await new Promise(resolve => server.close(resolve));
    return port;
}

/**
 * Converts a v2 character_book entry into the world file format SillyTavern
 * expects. Missing fields are backfilled by the client when it loads the file.
 */
function worldFromCharacterBook(book) {
    const entries = {};
    book.entries.forEach((entry, index) => {
        const uid = entry.id ?? index;
        entries[String(uid)] = {
            uid,
            key: entry.keys ?? [],
            keysecondary: entry.secondary_keys ?? [],
            comment: entry.comment ?? entry.name ?? '',
            content: entry.content ?? '',
            constant: entry.constant ?? false,
            selective: entry.selective ?? false,
            order: entry.insertion_order ?? 100,
            position: 0,
            disable: !(entry.enabled ?? true),
            addMemo: Boolean(entry.comment ?? entry.name),
            displayIndex: index,
            probability: 100,
            useProbability: true,
            depth: 4,
            extensions: entry.extensions ?? {},
        };
    });
    return { entries, originalData: book };
}

function writeChatFile(chatPath, card, messages) {
    const header = {
        user_name: 'User',
        character_name: card.data.name,
        create_date: '2024-01-01T00:00:00.000Z',
        chat_metadata: {},
    };
    const lines = [JSON.stringify(header)];
    messages.forEach((message, index) => {
        lines.push(JSON.stringify({
            name: message.name,
            is_user: Boolean(message.is_user),
            is_system: false,
            send_date: `Jan 1, 2024 00:00:0${index}`,
            mes: message.mes,
            extra: {},
            swipes: [message.mes],
            swipe_id: 0,
            swipe_info: [],
        }));
    });
    fs.writeFileSync(chatPath, lines.join('\n'));
}

/**
 * Creates a data root laid out like a normal SillyTavern install and copies in
 * the fixture character, lore, chat, settings and a placeholder API key.
 */
export function prepareDataRoot(caseSpec) {
    const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), `st-capture-${caseSpec.name}-`));
    const dataRoot = path.join(tmpRoot, 'data');
    const userRoot = path.join(dataRoot, 'default-user');
    const charactersDir = path.join(userRoot, 'characters');
    const chatsDir = path.join(userRoot, 'chats');
    const worldsDir = path.join(userRoot, 'worlds');
    for (const dir of [charactersDir, chatsDir, worldsDir]) {
        fs.mkdirSync(dir, { recursive: true });
    }

    const card = readJson(path.join(FIXTURES_DIR, caseSpec.card));
    const cardName = card.data.name;
    const avatarUrl = `${cardName}.png`;
    fs.writeFileSync(
        path.join(charactersDir, avatarUrl),
        writeCharacterCard(BASE_PNG, JSON.stringify(card)),
    );

    if (card.data.character_book) {
        const worldName = card.data.extensions?.world ?? `${cardName}'s Lorebook`;
        fs.writeFileSync(
            path.join(worldsDir, `${worldName}.json`),
            JSON.stringify(worldFromCharacterBook(card.data.character_book), null, 2),
        );
    }

    const chatFixture = readJson(path.join(FIXTURES_DIR, 'chat-plain.json'));
    const characterChatsDir = path.join(chatsDir, cardName);
    fs.mkdirSync(characterChatsDir, { recursive: true });
    writeChatFile(path.join(characterChatsDir, `${CHAT_FILE}.jsonl`), card, chatFixture);

    fs.copyFileSync(
        path.join(FIXTURES_DIR, caseSpec.settings),
        path.join(userRoot, 'settings.json'),
    );

    // The UI refuses to connect without a key on disk. The value is never sent:
    // the status probe and /generate are both fulfilled by Playwright.
    fs.writeFileSync(
        path.join(userRoot, 'secrets.json'),
        JSON.stringify({ api_key_openai: 'sk-fixture-not-used' }),
    );

    const configPath = path.join(tmpRoot, 'config.yaml');
    const port = caseSpec.port;
    fs.writeFileSync(configPath, [
        `dataRoot: ${JSON.stringify(dataRoot)}`,
        `port: ${port}`,
        'listen: false',
        'protocol:',
        '  ipv4: true',
        '  ipv6: false',
        'browserLaunch:',
        '  enabled: false',
        'whitelistMode: false',
        'enableUserAccounts: false',
        '',
    ].join('\n'));

    return { tmpRoot, dataRoot, configPath, cardName, avatarUrl };
}

function startServer({ configPath, dataRoot, port }) {
    const child = spawn(process.execPath, [
        'server.js',
        '--port', String(port),
        '--listen', 'false',
        '--configPath', configPath,
        '--dataRoot', dataRoot,
    ], {
        cwd: SERVER_DIR,
        env: { ...process.env, NODE_ENV: 'production' },
        stdio: ['ignore', 'pipe', 'pipe'],
    });

    const logs = [];
    const collect = chunk => {
        const text = String(chunk);
        logs.push(text);
        if (process.env.CAPTURE_VERBOSE) process.stdout.write(text);
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    return { child, logs };
}

async function waitForServer(child, logs, url, timeoutMs = 120_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (child.exitCode !== null) {
            throw new Error(`SillyTavern exited early (code ${child.exitCode}).\n${logs.join('')}`);
        }
        try {
            const response = await fetch(url, { redirect: 'manual' });
            if (response.status < 500) return;
        } catch {
            // not listening yet
        }
        await delay(250);
    }
    throw new Error(`SillyTavern did not start within ${timeoutMs}ms.\n${logs.join('')}`);
}

async function captureCase(caseSpec) {
    const port = await getFreePort();
    const { tmpRoot, dataRoot, configPath, cardName, avatarUrl } =
        prepareDataRoot({ ...caseSpec, port });
    const serverUrl = `http://127.0.0.1:${port}/`;
    const { child, logs } = startServer({ configPath, dataRoot, port });

    let browser;
    try {
        await waitForServer(child, logs, serverUrl);

        browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
        const context = await browser.newContext();
        const page = await context.newPage();
        const pageErrors = [];
        page.on('pageerror', error => pageErrors.push(String(error)));

        await page.route('**/api/backends/chat-completions/status', route => route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: [] }),
        }));

        let resolveCapture;
        const capturedPromise = new Promise(resolve => { resolveCapture = resolve; });
        await page.route('**/api/backends/chat-completions/generate', async route => {
            const request = route.request();
            const raw = request.postData() ?? '';
            await route.fulfill({
                status: 200,
                headers: { 'content-type': 'text/event-stream' },
                body: SSE_BODY,
            });
            resolveCapture({ url: request.url(), body: JSON.parse(raw) });
        });

        await page.goto(serverUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });

        await page.waitForFunction(() => {
            const st = window.SillyTavern;
            if (!st?.getContext) return false;
            const ctx = st.getContext();
            return Array.isArray(ctx.characters) && ctx.characters.length > 0
                && ctx.chatCompletionSettings?.chat_completion_source === 'openai';
        }, null, { timeout: 90_000 });

        // Establish the "connected" state so Generate() is allowed to run. The
        // status probe is intercepted above; the key check uses secrets.json.
        await page.evaluate(() => document.querySelector('#api_button_openai')?.click());
        await page.waitForFunction(() => {
            const status = window.SillyTavern.getContext().onlineStatus;
            return status && status !== 'no_connection';
        }, null, { timeout: 30_000 });

        const selection = await page.evaluate(async ({ cardName, chatFile }) => {
            const ctx = window.SillyTavern.getContext();
            const index = ctx.characters.findIndex(character => character.name === cardName);
            if (index < 0) throw new Error(`Fixture character not found: ${cardName}`);
            ctx.characters[index].chat = chatFile;
            await ctx.selectCharacterById(index, { switchMenu: false });
            return { index, chatLength: ctx.chat.length };
        }, { cardName, chatFile: CHAT_FILE });

        if (selection.chatLength < 2) {
            throw new Error(`Fixture chat did not load (length ${selection.chatLength})`);
        }

        await page.evaluate(text => {
            const textarea = document.querySelector('#send_textarea');
            textarea.value = text;
            textarea.dispatchEvent(new Event('input', { bubbles: true }));
        }, USER_INPUT);

        await page.evaluate(() => document.querySelector('#send_but').click());

        const captured = await Promise.race([
            capturedPromise,
            delay(60_000).then(() => {
                throw new Error(`No /generate request intercepted. Page errors: ${pageErrors.join(' | ')}`);
            }),
        ]);

        assertCapture(caseSpec, captured.body);
        fs.writeFileSync(
            path.join(OUTPUT_DIR, `${caseSpec.name}.json`),
            JSON.stringify({
                name: caseSpec.name,
                url: captured.url,
                body: captured.body,
                character: cardName,
                avatarUrl,
                chatFile: CHAT_FILE,
                userInput: USER_INPUT,
                _variant: caseSpec.variant,
            }, null, 2) + '\n',
        );
        console.log(`captured ${caseSpec.name}: ${captured.body.messages.length} messages, model=${captured.body.model}`);
    } finally {
        await browser?.close().catch(() => {});
        if (child.exitCode === null) {
            child.kill('SIGTERM');
            const exited = await Promise.race([
                new Promise(resolve => child.once('exit', () => resolve(true))),
                delay(5_000).then(() => false),
            ]);
            if (!exited) child.kill('SIGKILL');
        }
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
}

/**
 * The capture is the ground truth for Task 2, so fail loudly if it is empty or
 * if the fixture data did not reach the prompt.
 */
function assertCapture(caseSpec, body) {
    const fail = message => {
        throw new Error(`[${caseSpec.name}] ${message}`);
    };
    if (!Array.isArray(body.messages) || body.messages.length === 0) fail('body.messages is not a non-empty array');
    if (body.model !== MODEL) fail(`body.model is ${body.model}, expected ${MODEL}`);

    const serialized = JSON.stringify(body.messages);
    if (!serialized.includes('Fixture Plain') && !serialized.includes('Fixture Worldinfo')) {
        fail('character name is missing from body.messages');
    }
    if (!serialized.includes(USER_INPUT)) fail('user input is missing from body.messages');
    if (!serialized.includes('Tell me about apples.')) fail('fixture chat message is missing from body.messages');

    const hasWorldInfo = serialized.includes('APPLE FACT: apples are red.');
    if (caseSpec.expectWorldInfo && !hasWorldInfo) fail('world info entry was not activated');
    if (!caseSpec.expectWorldInfo && hasWorldInfo) fail('world info entry leaked into a non-worldinfo fixture');

    if (caseSpec.variant) {
        if (Number(body.temperature) !== caseSpec.variant.temp_openai) {
            fail(`body.temperature is ${body.temperature}, expected ${caseSpec.variant.temp_openai}`);
        }
        if (Number(body.top_p) !== caseSpec.variant.top_p_openai) {
            fail(`body.top_p is ${body.top_p}, expected ${caseSpec.variant.top_p_openai}`);
        }
    }
}

async function main() {
    fs.mkdirSync(OUTPUT_DIR, { recursive: true });
    for (const caseSpec of CASES) {
        await captureCase(caseSpec);
    }
    console.log(`reference captures written to ${OUTPUT_DIR}`);
}

// Exported helpers are reused by build-headless.mjs; only run the capture when
// this file is executed directly.
const invokedDirectly = process.argv[1]
    && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

if (invokedDirectly) {
    main().catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
}
