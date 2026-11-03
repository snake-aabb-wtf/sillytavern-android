/**
 * Headless prompt builder.
 *
 * Boots the real SillyTavern server against a prepared data root, loads the
 * unmodified web client into jsdom (see dom-host.mjs), drives the same "select
 * character, load chat, type, send" path the Playwright capture used, and
 * returns the exact JSON body the client POSTs to
 * /api/backends/chat-completions/generate.
 *
 * The client's module graph is a process-wide singleton (module-level settings
 * and chat state), so one createBuilder() call is meant per process. The parity
 * test spawns one child process per fixture.
 *
 * Usage as a library:
 *   const builder = await createBuilder({ dataDir, port });
 *   const { url, body } = await builder.build({ avatarUrl, fileName, userInput, type: 'normal' });
 *   await builder.close();
 *
 * Usage as a CLI (one fixture per process):
 *   node prompt-builder/index.mjs --spec <spec.json>
 */
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { createDomHost } from './dom-host.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_SERVER_DIR = path.resolve(HERE, '../../SillyTavern');

export const GENERATE_PATH = '/api/backends/chat-completions/generate';
export const STATUS_PATH = '/api/backends/chat-completions/status';

// A minimal OpenAI-style SSE body: one content chunk and the terminator. The
// request body is what matters here; the client only has to stop streaming.
export const SSE_BODY = 'data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function getFreePort() {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    const { port } = server.address();
    await new Promise(resolve => server.close(resolve));
    return port;
}

function writeServerConfig(configPath, { dataDir, port }) {
    fs.writeFileSync(configPath, [
        `dataRoot: ${JSON.stringify(dataDir)}`,
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
}

function startServer({ serverDir, configPath, dataDir, port, log }) {
    const child = spawn(process.execPath, [
        'server.js',
        '--port', String(port),
        '--listen', 'false',
        '--configPath', configPath,
        '--dataRoot', dataDir,
    ], {
        cwd: serverDir,
        env: { ...process.env, NODE_ENV: 'production' },
        stdio: ['ignore', 'pipe', 'pipe'],
    });

    const collect = chunk => {
        const text = String(chunk);
        for (const line of text.split('\n')) {
            if (line.trim()) log(`[server] ${line}`);
        }
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    child.on('exit', code => {
        process.stderr.write(`[builder] SillyTavern server exited (${code})\n`);
    });
    return child;
}

async function waitForServer(child, baseUrl, timeoutMs = 180_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (child.exitCode !== null) {
            throw new Error(`SillyTavern exited early (code ${child.exitCode})`);
        }
        try {
            const response = await fetch(baseUrl, { redirect: 'manual' });
            if (response.status < 500) return;
        } catch {
            // not listening yet
        }
        await delay(250);
    }
    throw new Error(`SillyTavern did not start within ${timeoutMs}ms`);
}

async function stopServer(child) {
    if (!child || child.exitCode !== null) return;
    child.kill('SIGTERM');
    const exited = await Promise.race([
        new Promise(resolve => child.once('exit', () => resolve(true))),
        delay(5_000).then(() => false),
    ]);
    if (!exited) child.kill('SIGKILL');
}

/**
 * @param {object} options
 * @param {string} options.dataDir Directory that already contains default-user/{characters,chats,worlds,settings.json,secrets.json}.
 * @param {number} [options.port] Port for the temporary server; a free one is chosen when omitted.
 * @param {string} [options.serverDir] SillyTavern checkout; defaults to the repo's SillyTavern/.
 * @param {(line: string) => void} [options.onLog]
 * @returns {Promise<{build: Function, close: Function, baseUrl: string, port: number}>}
 */
export async function createBuilder({ dataDir, port, serverDir = DEFAULT_SERVER_DIR, onLog = () => {} }) {
    const log = line => onLog(String(line));
    if (!fs.existsSync(dataDir)) {
        throw new Error(`dataDir does not exist: ${dataDir}`);
    }

    const workRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'st-headless-'));
    const configPath = path.join(workRoot, 'config.yaml');
    const resolvedPort = port ?? await getFreePort();
    writeServerConfig(configPath, { dataDir, port: resolvedPort });

    const baseUrl = `http://127.0.0.1:${resolvedPort}/`;
    const child = startServer({ serverDir, configPath, dataDir, port: resolvedPort, log });

    let host;
    let captured = null;
    let resolveCapture = null;
    let rejectCapture = null;

    try {
        await waitForServer(child, baseUrl);
        log(`[builder] server ready at ${baseUrl}`);

        host = await createDomHost({
            baseUrl,
            publicDir: path.join(serverDir, 'public'),
            onLog: log,
            intercept: (url, init) => {
                if (url.includes(GENERATE_PATH)) {
                    let body;
                    try {
                        body = JSON.parse(String(init.body ?? ''));
                    } catch (error) {
                        rejectCapture?.(new Error(`Could not parse ${GENERATE_PATH} body: ${error?.message || error}`));
                        return new Response('invalid body', { status: 400 });
                    }
                    const record = { url, body };
                    if (resolveCapture) {
                        resolveCapture(record);
                    } else {
                        captured = record;
                    }
                    return new Response(SSE_BODY, {
                        status: 200,
                        headers: { 'content-type': 'text/event-stream' },
                    });
                }
                if (url.includes(STATUS_PATH)) {
                    // Mirrors what the Playwright capture fulfilled.
                    return new Response(JSON.stringify({ data: [] }), {
                        status: 200,
                        headers: { 'content-type': 'application/json' },
                    });
                }
                return null;
            },
        });
    } catch (error) {
        await stopServer(child);
        fs.rmSync(workRoot, { recursive: true, force: true });
        throw error;
    }

    const { window } = host;
    const ctx = () => {
        const st = window.SillyTavern ?? globalThis.SillyTavern;
        if (!st?.getContext) throw new Error('window.SillyTavern.getContext is not available');
        return st.getContext();
    };

    async function ensureOnline() {
        const context = ctx();
        if (context.onlineStatus && context.onlineStatus !== 'no_connection') return;

        // Same click the capture performs; it triggers a status probe that the
        // fetch intercept above answers.
        const button = window.document.querySelector('#api_button_openai');
        button?.click();
        try {
            await host.waitFor(() => {
                const status = ctx().onlineStatus;
                return status && status !== 'no_connection';
            }, { timeoutMs: 30_000, label: 'online status after #api_button_openai click' });
        } catch {
            // Fall back to the exported setter: only "connected" matters for
            // Generate(), and the probe result is not part of the request body.
            const setOnlineStatus = host.app?.setOnlineStatus;
            if (typeof setOnlineStatus !== 'function') throw new Error('Could not establish an online status');
            setOnlineStatus('openai');
        }
    }

    /**
     * @param {object} options
     * @param {string} options.avatarUrl Character avatar file name, e.g. "Fixture Plain.png".
     * @param {string} options.fileName Chat file name without extension.
     * @param {string} options.userInput Text to send.
     * @param {string} [options.type] Generation type; the capture used "normal".
     * @param {number} [options.timeoutMs]
     * @returns {Promise<{url: string, body: object}>}
     */
    async function build({ avatarUrl, fileName, userInput, type = 'normal', timeoutMs = 180_000 }) {
        if (type !== 'normal') {
            // The captured fixtures are all "normal" sends. Regenerate/swipe
            // mutate the chat differently; keep the API honest rather than
            // silently producing a different body.
            throw new Error(`build(): unsupported type ${JSON.stringify(type)}; only "normal" is implemented`);
        }

        await host.waitFor(() => Array.isArray(ctx().characters) && ctx().characters.length > 0, {
            timeoutMs: 60_000,
            label: 'characters to load',
        });
        await ensureOnline();

        const characters = ctx().characters;
        const index = characters.findIndex(character => character.avatar === avatarUrl);
        if (index < 0) {
            throw new Error(`Character not found: ${avatarUrl} (have: ${characters.map(c => c.avatar).join(', ')})`);
        }

        characters[index].chat = fileName;
        await ctx().selectCharacterById(index, { switchMenu: false });
        await host.waitFor(() => Array.isArray(ctx().chat) && ctx().chat.length >= 2, {
            timeoutMs: 60_000,
            label: 'fixture chat to load',
        });

        const textarea = window.document.querySelector('#send_textarea');
        if (!textarea) throw new Error('#send_textarea is missing');
        textarea.value = userInput;
        textarea.dispatchEvent(new window.Event('input', { bubbles: true }));

        const capturedPromise = new Promise((resolve, reject) => {
            resolveCapture = resolve;
            rejectCapture = reject;
        });

        const sendButton = window.document.querySelector('#send_but');
        if (!sendButton) throw new Error('#send_but is missing');
        sendButton.click();

        const timeout = delay(timeoutMs).then(() => {
            throw new Error(`No ${GENERATE_PATH} request was assembled within ${timeoutMs}ms`);
        });

        if (captured) return captured;
        return await Promise.race([capturedPromise, timeout]);
    }

    async function close() {
        host?.close();
        await stopServer(child);
        fs.rmSync(workRoot, { recursive: true, force: true });
    }

    return { build, close, baseUrl, port: resolvedPort };
}

/* ------------------------------------------------------------------ */
/* CLI: build one fixture in a fresh process.                          */
/* ------------------------------------------------------------------ */

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

function argValue(name) {
    const index = process.argv.indexOf(name);
    return index >= 0 ? process.argv[index + 1] : undefined;
}

async function main() {
    // The client is full of fire-and-forget UI promises. Report them instead
    // of letting Node abort the whole build on the first one.
    process.on('unhandledRejection', error => {
        process.stderr.write(`[builder] unhandled rejection: ${error?.stack || error}\n`);
    });

    const specPath = argValue('--spec');
    if (!specPath) {
        throw new Error('Usage: node prompt-builder/index.mjs --spec <spec.json>');
    }
    const spec = JSON.parse(fs.readFileSync(specPath, 'utf8'));
    const verbose = process.env.HEADLESS_VERBOSE === '1';
    const log = line => {
        if (verbose) process.stderr.write(`${line}\n`);
    };

    const builder = await createBuilder({
        dataDir: spec.dataDir,
        port: spec.port,
        serverDir: spec.serverDir,
        onLog: log,
    });

    const startedAt = Date.now();
    let result;
    try {
        result = await builder.build({
            avatarUrl: spec.avatarUrl,
            fileName: spec.fileName,
            userInput: spec.userInput,
            type: spec.type ?? 'normal',
        });
    } finally {
        const elapsedMs = Date.now() - startedAt;
        await builder.close().catch(() => {});
        if (result) {
            fs.mkdirSync(path.dirname(spec.out), { recursive: true });
            fs.writeFileSync(spec.out, JSON.stringify({
                name: spec.name,
                url: result.url,
                body: result.body,
                elapsedMs,
            }, null, 2) + '\n');
            process.stdout.write(`headless ${spec.name}: ${result.body.messages.length} messages, ${elapsedMs}ms\n`);
        }
    }
    process.exit(0);
}

if (isMain) {
    main().catch(error => {
        process.stderr.write(`${error?.stack || error}\n`);
        process.exit(1);
    });
}
