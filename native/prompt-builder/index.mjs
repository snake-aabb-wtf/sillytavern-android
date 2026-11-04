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

/**
 * Reads `main_api` from the user's settings file. This is the effective
 * backend: saving a provider through the app writes this file, while the
 * jsdom client keeps the value it read during page initialisation.
 */
export function readSettingsMainApi(dataDir) {
    const settingsPath = path.join(dataDir, 'default-user', 'settings.json');
    try {
        const raw = fs.readFileSync(settingsPath, 'utf8').replace(/^\uFEFF/, '');
        const parsed = JSON.parse(raw);
        return typeof parsed.main_api === 'string' ? parsed.main_api : null;
    } catch {
        return null;
    }
}

/**
 * The headless builder can only reproduce an OpenAI-compatible send. The seeded
 * settings default to `main_api: "koboldhorde"`; Generate() then never posts to
 * the chat-completions route, and build() would wait out its whole 180 s timeout
 * on the very first message of a fresh install. Fail before the send instead,
 * with the backend name and the action that actually clears it.
 *
 * `onDisk` is the value build() re-read from settings.json. When it already says
 * `openai` but the page still has another value, saving again cannot help: only
 * a restart reloads the client's boot-time snapshot, and the message says so.
 *
 * Exported so the preflight can be unit-tested without booting jsdom.
 */
export function assertSupportedBackend(mainApi, { onDisk } = {}) {
    if (mainApi === 'openai') return;
    const current = mainApi || '(not set)';
    const action = onDisk === 'openai'
        ? 'settings.json now selects openai, but the running app still uses the old value; restart the app to apply it'
        : 'open 设置 and save a provider (OpenAI 兼容 / OpenRouter / OpenAI 官方)';
    throw new Error(`build(): unsupported backend ${JSON.stringify(current)}; ${action}`);
}
export const STATUS_PATH = '/api/backends/chat-completions/status';

// The stub answer the client receives after it has POSTed /generate. The
// request body is captured before this response is consumed, so its content
// cannot change what the model receives. An error status is deliberate: the
// success path appends the stub to the transcript, saves the chat and triggers
// a PromptManager dry-run re-render (measured at 1-4 s per turn), while the
// error path just unblocks generation. build() swallows the rejection it
// causes; page-originated chat saves are blocked, so nothing is persisted.
// Deliberately empty: a JSON error body makes the client show a toast (and
// that toast's DOM/animation work costs a second in jsdom), while an empty
// body still fails the response cleanly with the same status.
export const STUB_FAILURE_BODY = '';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Canonical form for comparing chat metadata. Key order is irrelevant, and the
 * volatile bookkeeping fields are excluded: `integrity` is a per-session uuid
 * generated client-side when a file has none, `tainted` is set by every
 * generated message, and `timedWorldInfo` / `lastInContextMessageId` /
 * `pick_reroll_seed` / `chat_id_hash` are runtime state the client rebuilds
 * itself. None of them says anything about whether the chat file changed.
 */
function canonicalManifest(value) {
    if (Array.isArray(value)) return value.map(canonicalManifest);
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonicalManifest(value[key])]));
    }
    return value;
}

function normalizeManifest(metadata) {
    const copy = { ...(metadata && typeof metadata === 'object' ? metadata : {}) };
    for (const key of ['integrity', 'tainted', 'timedWorldInfo', 'lastInContextMessageId', 'pick_reroll_seed', 'chat_id_hash']) {
        delete copy[key];
    }
    return copy;
}

function sameManifest(left, right) {
    return JSON.stringify(canonicalManifest(normalizeManifest(left)))
        === JSON.stringify(canonicalManifest(normalizeManifest(right)));
}

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
    // The client runs its normal post-generation path against the canned SSE
    // stub: it appends the reply and calls saveChatConditional, and a debounced
    // save can fire after build() has already returned. None of those writes
    // may reach the real chat file - the reply is a stub, not the provider's
    // answer - so every page-originated chat save is answered locally. The
    // caller's own saves go through its own HTTP client, not this wrapper.

    // Chat identity and file-side metadata of the state currently loaded into
    // the client. Used to skip CHAT_CHANGED/CHAT_LOADED work when a turn
    // reloads the same unchanged chat: the comparison is between what the file
    // said last time and what it says now, so runtime-only fields the client
    // keeps in memory cannot cause a spurious chat-switch notification.
    let lastChatKey = null;
    let lastChatManifest = null;
    // Monotonic count of chat states applied by loadChatState(). build() waits
    // on this instead of a message count: a chat created by the native client
    // legitimately has zero or one message, so `chat.length >= 2` only held for
    // the captured two-message fixtures and hung on every fresh chat.
    let chatStateGeneration = 0;

    try {
        await waitForServer(child, baseUrl);
        log(`[builder] server ready at ${baseUrl}`);

        host = await createDomHost({
            baseUrl,
            publicDir: path.join(serverDir, 'public'),
            onLog: log,
            intercept: (url, init) => {
                if (/\/api\/chats\/(group\/)?save(?:[?#]|$)/.test(url)) {
                    // Pretend the save succeeded, exactly like the real route.
                    return new Response(JSON.stringify({ ok: true }), {
                        status: 200,
                        headers: { 'content-type': 'application/json' },
                    });
                }
                if (url.includes(GENERATE_PATH)) {
                    log(`[builder] captured generate request (pending=${Boolean(resolveCapture)})`);
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
                    return new Response(STUB_FAILURE_BODY, {
                        status: 500,
                        headers: { 'content-type': 'text/plain' },
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

    // PromptManager re-renders the context-composition panel after every
    // message, and each render runs a full dry-run prompt assembly inside
    // jsdom (measured ~2 s per turn) purely to draw the panel. It cannot affect
    // the /generate body, so disable the debounced re-render for this headless
    // client. The one-shot render done while the manager is created stays.
    let openaiModule = null;
    try {
        const openaiUrl = pathToFileURL(path.join(serverDir, 'public', 'scripts', 'openai.js')).href;
        const openai = await import(openaiUrl);
        openaiModule = openai;
        if (openai.promptManager?.renderDebounced) {
            openai.promptManager.renderDebounced = () => {};
        } else {
            log('[builder] PromptManager not initialised; its re-render stays enabled');
        }
    } catch (error) {
        log(`[builder] could not disable PromptManager re-render: ${error?.message || error}`);
    }

    // Sending a message schedules debounced chat/metadata saves. They can only
    // write the builder's stub state, and in jsdom their compression work lands
    // ~1 s after the send and blocks the turn (measured 1.6 s). The builder
    // never wants them, so cancel them as soon as the request is captured.
    let extensionsModule = null;
    try {
        extensionsModule = await import(pathToFileURL(path.join(serverDir, 'public', 'scripts', 'extensions.js')).href);
    } catch (error) {
        log(`[builder] could not load extension helpers: ${error?.message || error}`);
    }
    const cancelPendingSaves = () => {
        try {
            host.app?.cancelDebouncedChatSave?.();
        } catch {
            // best effort
        }
        try {
            extensionsModule?.cancelDebouncedMetadataSave?.();
        } catch {
            // best effort
        }
    };

    const { window } = host;

    const ctx = () => {
        const st = window.SillyTavern ?? globalThis.SillyTavern;
        if (!st?.getContext) throw new Error('window.SillyTavern.getContext is not available');
        return st.getContext();
    };

    /**
     * Re-reads the provider block from settings.json into the live client.
     *
     * Only the two pieces the send body depends on are refreshed: the OpenAI
     * provider settings (model, URL, presets) and the selected backend. A full
     * `getSettings()` re-runs extension/theme/onboarding boot work that jsdom
     * does not need and can take minutes; this is the same subset that produced
     * the boot-time state, applied again from disk.
     */
    async function refreshClientProvider() {
        const context = ctx();
        const response = await window.fetch('/api/settings/get', {
            method: 'POST',
            headers: context.getRequestHeaders(),
            cache: 'no-cache',
            body: JSON.stringify({}),
        });
        if (!response.ok) throw new Error(`POST /api/settings/get failed (${response.status})`);
        const data = await response.json();
        if (data?.result === 'file not find' || !data?.settings) {
            throw new Error('/api/settings/get returned no settings');
        }
        if (typeof openaiModule?.loadOpenAISettings !== 'function') {
            throw new Error('loadOpenAISettings is not available; cannot refresh the provider');
        }
        if (typeof host.app?.changeMainAPI !== 'function') {
            throw new Error('changeMainAPI is not available; cannot refresh the backend');
        }
        const settings = JSON.parse(data.settings);
        openaiModule.loadOpenAISettings(data, settings);
        host.app.changeMainAPI(settings.main_api);
    }

    /**
     * The backend build() should dispatch on.
     *
     * `ctx().mainApi` is a boot-time snapshot: the client reads settings once
     * while the page initialises, and the builder is warmed at server start and
     * reused for the whole app process. A provider saved since then exists only
     * in settings.json, so reading the snapshot alone would tell the user to do
     * the thing they just did, forever. Read the file per build and, when it
     * disagrees, make the client re-read its provider settings once before
     * deciding.
     */
    async function resolveEffectiveBackend() {
        const onDisk = readSettingsMainApi(dataDir);
        const cached = ctx().mainApi;
        if (!onDisk || onDisk === cached) return { mainApi: cached, onDisk };

        try {
            await refreshClientProvider();
        } catch (error) {
            log(`[builder] settings refresh failed: ${error?.message || error}`);
            return { mainApi: cached, onDisk };
        }
        return { mainApi: ctx().mainApi, onDisk };
    }

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
     * Loads one chat into the client's state without reloadCurrentChat().
     *
     * reloadCurrentChat() -> clearChat() -> getChat() -> getChatResult()
     * clears the transcript and then calls printMessages(), which re-renders
     * every message (markdown + DOMPurify per message) inside jsdom. None of
     * that DOM feeds the /generate body: prompt assembly reads the `chat`,
     * `chat_metadata` and extension-prompt state. This mirrors the state part
     * of getChat()/getChatResult() (script.js:7634-7710) and skips the render.
     *
     * Returns `{ applied: false }` when the chat has no messages, so the caller
     * can fall back to the full client path (which materialises the first
     * message). The fetched header travels with the result: it is the identity
     * the reloaded state must match.
     */
    async function loadChatState({ character, index, fileName }) {
        const startedAt = performance.now();
        const context = ctx();
        const response = await window.fetch('/api/chats/get', {
            method: 'POST',
            headers: context.getRequestHeaders(),
            cache: 'no-cache',
            body: JSON.stringify({
                ch_name: character.name,
                file_name: fileName,
                avatar_url: character.avatar,
            }),
        });
        if (!response.ok) throw new Error(`POST /api/chats/get failed (${response.status})`);
        const data = await response.json();
        const fetchedAt = performance.now();
        if (!Array.isArray(data) || data.length === 0) return { applied: false, header: null };
        const header = data.shift() ?? {};
        const nextMetadata = header.chat_metadata && typeof header.chat_metadata === 'object'
            ? header.chat_metadata
            : {};
        const metadata = context.chatMetadata;
        const key = `${index}:${fileName}`;
        // reloadCurrentChat() emits CHAT_CHANGED on every load. Those handlers
        // (persona selection, preset auto-pick, chat-lorebook pre-cache, ...)
        // only need to run when the chat or its metadata actually changed: the
        // real UI does not reload at all between two turns of one chat, so
        // re-notifying would apply work that belongs to a chat switch.
        const nextManifest = normalizeManifest(nextMetadata);
        const keyChanged = key !== lastChatKey;
        const notify = keyChanged || (lastChatManifest !== null && !sameManifest(lastChatManifest, nextManifest));
        const previousIntegrity = metadata?.integrity;

        const liveChat = context.chat;
        liveChat.splice(0, liveChat.length, ...data);
        for (const message of data) context.ensureMessageMediaIsArray?.(message);

        if (metadata && typeof metadata === 'object') {
            for (const k of Object.keys(metadata)) delete metadata[k];
            Object.assign(metadata, nextMetadata);
            if (!metadata.integrity && previousIntegrity) metadata.integrity = previousIntegrity;
            if (!metadata.integrity && typeof context.uuidv4 === 'function') metadata.integrity = context.uuidv4();
        }
        if (notify) {
            log(`[builder] chat notify: keyChanged=${keyChanged} ` +
                `from=${JSON.stringify(canonicalManifest(lastChatManifest))} ` +
                `to=${JSON.stringify(canonicalManifest(nextManifest))}`);
            // clearChat() resets extension prompts on a real reload; extensions
            // re-add theirs from CHAT_CHANGED/CHAT_LOADED below.
            const extensionPrompts = context.extensionPrompts;
            if (extensionPrompts && typeof extensionPrompts === 'object') {
                for (const k of Object.keys(extensionPrompts)) delete extensionPrompts[k];
            }
        }
        const appliedAt = performance.now();

        if (notify && context.eventTypes && context.eventSource) {
            await context.eventSource.emit(context.eventTypes.CHAT_CHANGED, context.chatId);
            await context.eventSource.emit(context.eventTypes.CHAT_LOADED, { detail: { id: index, character } });
        }
        lastChatKey = key;
        lastChatManifest = nextManifest;
        chatStateGeneration += 1;
        const eventsAt = performance.now();
        log(`[builder] chat load (ms): fetch=${Math.round(fetchedAt - startedAt)} ` +
            `apply=${Math.round(appliedAt - fetchedAt)} events=${Math.round(eventsAt - appliedAt)} ` +
            `notify=${notify} messages=${data.length}`);
        return { applied: true, header };
    }

    /**
     * Fails when the client's live chat is not the one build() asked for.
     *
     * `character.chat` and `lastChatKey` are values this builder set itself, so
     * they cannot prove the reload happened. The chat file's integrity (written
     * by NewChatService and by the client on first save) and the message list
     * are read from the requested file, and both must appear live.
     */
    function assertRequestedChatApplied({ expectedMetadata, fileName, greeting }) {
        const context = ctx();
        const expectedIntegrity = expectedMetadata?.integrity;
        const liveIntegrity = context.chatMetadata?.integrity;
        if (expectedIntegrity && liveIntegrity !== expectedIntegrity) {
            throw new Error(
                `build(): the chat reload did not apply ${JSON.stringify(fileName)}; ` +
                `chat_metadata.integrity is ${JSON.stringify(liveIntegrity ?? null)} but the file has ` +
                `${JSON.stringify(expectedIntegrity)}; refusing to build from the previous chat state`,
            );
        }

        const currentId = typeof context.getCurrentChatId === 'function'
            ? context.getCurrentChatId()
            : undefined;
        if (currentId && fileName && currentId !== fileName) {
            throw new Error(
                `build(): the client is on chat ${JSON.stringify(currentId)}, not ${JSON.stringify(fileName)}; ` +
                'refusing to build from the previous chat state',
            );
        }

        // Older chats have no integrity, so compare the rest of the header too.
        // normalizeManifest drops the fields the client legitimately rewrites.
        if (expectedMetadata && !sameManifest(expectedMetadata, context.chatMetadata)) {
            throw new Error(
                `build(): chat_metadata for ${JSON.stringify(fileName)} does not match the file; ` +
                'refusing to build from the previous chat state',
            );
        }

        const messages = Array.isArray(context.chat) ? context.chat : [];
        // The requested file was header-only, so a successful reload leaves the
        // materialised greeting (one message) or nothing at all. Any other
        // length is the previous chat's transcript.
        const expected = greeting ? 1 : 0;
        if (messages.length !== expected) {
            throw new Error(
                `build(): ${JSON.stringify(fileName)} loaded ${messages.length} message(s), expected ${expected}; ` +
                'refusing to build from the previous chat state',
            );
        }
    }

    /**
     * @param {object} options
     * @param {string} options.avatarUrl Character avatar file name, e.g. "Fixture Plain.png".
     * @param {string} options.fileName Chat file name without extension.
     * @param {string} options.userInput Text to send.
     * @param {string} [options.type] Generation type; the capture used "normal".
     * @param {number} [options.timeoutMs]
     * @returns {Promise<{url: string, body: object, timings: object}>}
     */
    async function build({ avatarUrl, fileName, userInput, type = 'normal', timeoutMs = 180_000 }) {
        if (type !== 'normal') {
            // The captured fixtures are all "normal" sends. Regenerate/swipe
            // mutate the chat differently; keep the API honest rather than
            // silently producing a different body.
            throw new Error(`build(): unsupported type ${JSON.stringify(type)}; only "normal" is implemented`);
        }

        // Phase marks. The client is a jsdom singleton, so "how long did the
        // turn take" is only actionable split by stage: reloading/rendering a
        // chat and assembling the prompt have completely different fixes.
        const startedAt = performance.now();
        const marks = {};
        const mark = name => {
            marks[name] = performance.now();
        };
        mark('start');

        await host.waitFor(() => Array.isArray(ctx().characters) && ctx().characters.length > 0, {
            timeoutMs: 60_000,
            label: 'characters to load',
        });
        mark('characters');
        // A fresh install seeds main_api: koboldhorde; Generate() would then
        // never reach the chat-completions route and the first send would hang
        // for the full 180 s timeout. Reject it before touching the UI, using
        // the value on disk (the client's snapshot is from page boot).
        const backend = await resolveEffectiveBackend();
        assertSupportedBackend(backend.mainApi, { onDisk: backend.onDisk });
        await ensureOnline();
        mark('online');

        const characters = ctx().characters;
        const index = characters.findIndex(character => character.avatar === avatarUrl);
        if (index < 0) {
            throw new Error(`Character not found: ${avatarUrl} (have: ${characters.map(c => c.avatar).join(', ')})`);
        }

        let character = characters[index];
        character.chat = fileName;

        // Both the first and later turns use loadChatState(). Turn 1 used to go
        // through selectCharacterById(), whose getChat() -> getChatResult() runs
        // clearChat(), printMessages() and select_selected_character(); none of
        // that reaches the /generate body, and it kept parity (which builds
        // once, in a fresh process) testing the old path rather than this one.
        //
        // The state getChatResult() establishes around the chat load has to be
        // replicated explicitly: unshallowCharacter() hydrates a shallow card,
        // setCharacterId() points getCurrentChatId()/world info at this chat,
        // and setCharacterName() sets `name2`, which is what {{char}} and the
        // chat-completion name resolve to. Leaving `name2` at the persona name
        // silently corrupts the prompt.
        const app = host.app ?? {};
        const canAdoptDirectly =
            typeof app.setCharacterId === 'function' &&
            typeof app.setCharacterName === 'function';

        // Everything below loads exactly one chat. The marker is advanced when
        // that chat's header and messages have been applied - not when some
        // message count is reached, because a new chat has 0 or 1 messages.
        const chatGenerationBefore = chatStateGeneration;

        if (canAdoptDirectly) {
            if (String(ctx().characterId) !== String(index)) {
                if (typeof app.unshallowCharacter === 'function') {
                    await app.unshallowCharacter(index);
                    // getOneCharacter() replaces the array entry with the full card.
                    character = ctx().characters[index];
                    character.chat = fileName;
                }
                app.setCharacterId(index);
                app.setCharacterName(character.name);
            }
            mark('select');

            // This builder is memoised for the whole server lifetime, so each
            // turn must re-read the chat: the caller saves the real assistant
            // reply between turns and the restored in-memory array would
            // silently drop it.
            const loaded = await loadChatState({ character, index, fileName });
            if (!loaded.applied) {
                // Empty chat: the real path materialises the greeting; keep it
                // (and its save) rather than inventing a different history.
                //
                // reloadCurrentChatUnsafe is exported by upstream but is not on
                // getContext() (scripts/st-context.js), so it is unreachable
                // here; the mutex-wrapped reload is the only available path.
                const reload = ctx().reloadCurrentChat;
                if (typeof reload !== 'function') throw new Error('reloadCurrentChat is not available; cannot reload the chat for this turn');
                await reload();
                lastChatKey = `${index}:${fileName}`;
                lastChatManifest = normalizeManifest(ctx().chatMetadata);

                // Post-condition: the live state must be the chat that was
                // requested, not whatever the previous turn left behind. A
                // no-op reload (mutex held, stale marker) used to pass when the
                // card simply had no greeting; the file's integrity and the
                // resulting message list identify the chat unambiguously.
                const liveCard = ctx().characters?.[index] ?? character;
                const greeting = liveCard?.first_mes
                    || liveCard?.data?.first_mes
                    || liveCard?.data?.alternate_greetings?.[0]
                    || '';
                assertRequestedChatApplied({
                    expectedMetadata: loaded.header?.chat_metadata,
                    fileName,
                    greeting,
                });
                chatStateGeneration += 1;
            }
            mark('chat');
        } else {
            // A client without the exported setters still works, it just pays
            // for the render: getChat() leaves the same state behind.
            await ctx().selectCharacterById(index, { switchMenu: false });
            lastChatKey = `${index}:${fileName}`;
            lastChatManifest = normalizeManifest(ctx().chatMetadata);
            chatStateGeneration += 1;
            mark('select');
            mark('chat');
        }

        await host.waitFor(
            () => Array.isArray(ctx().chat) && chatStateGeneration > chatGenerationBefore,
            { timeoutMs: 60_000, label: 'requested chat to be applied' },
        );
        mark('chatReady');

        const textarea = window.document.querySelector('#send_textarea');
        if (!textarea) throw new Error('#send_textarea is missing');
        textarea.value = userInput;
        textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
        mark('input');

        // Roll back the client's own bookkeeping: the activation sends a real
        // user message, receives the canned SSE stub, and runs the normal
        // post-generation path (append reply, save chat, save settings). The
        // snapshot plus the save block above leave both memory and disk at the
        // pre-send state, so the next build sees the user's real history.
        const chatSnapshot = JSON.parse(JSON.stringify(ctx().chat ?? []));
        const metadataSnapshot = JSON.parse(JSON.stringify(ctx().chatMetadata ?? {}));
        const restoreChatState = () => {
            const liveChat = ctx().chat;
            if (Array.isArray(liveChat)) {
                liveChat.splice(0, liveChat.length, ...chatSnapshot);
            }
            const liveMetadata = ctx().chatMetadata;
            if (liveMetadata && typeof liveMetadata === 'object') {
                for (const key of Object.keys(liveMetadata)) delete liveMetadata[key];
                Object.assign(liveMetadata, metadataSnapshot);
            }
        };

        const capturedPromise = new Promise((resolve, reject) => {
            resolveCapture = resolve;
            rejectCapture = reject;
        });

        const sendButton = window.document.querySelector('#send_but');
        if (!sendButton) throw new Error('#send_but is missing');

        let record;
        let generationSettled = null;
        try {
            log(`[builder] triggering send (generating=${host.app?.isGenerating?.()})`);
            // Call the exported send path directly instead of clicking: the
            // click handler only wraps it in a mutex and leaves its promise
            // unattached, which would turn the stub's intentional rejection
            // into an unhandled rejection in the host process. Its settlement
            // is also the precise "generation finished" signal; polling
            // isGenerating() can lag behind a busy jsdom event loop.
            const sendTextareaMessage = host.app?.sendTextareaMessage;
            if (typeof sendTextareaMessage === 'function') {
                const generation = sendTextareaMessage();
                if (typeof generation?.then === 'function') {
                    generationSettled = generation.then(
                        () => {},
                        error => log(`[builder] stub generation rejected (expected): ${error?.message || error}`),
                    );
                }
            } else {
                sendButton.click();
            }
            mark('click');

            const timeout = delay(timeoutMs).then(() => {
                throw new Error(`No ${GENERATE_PATH} request was assembled within ${timeoutMs}ms`);
            });

            record = captured ?? await Promise.race([capturedPromise, timeout]);
            mark('capture');
            cancelPendingSaves();

            // The fetch intercept resolves before the client reacts to the
            // stub. Wait until the send path has settled, so no client work can
            // touch the chat/metadata after the rollback below.
            if (generationSettled) {
                await Promise.race([generationSettled, delay(30_000)]);
            } else if (typeof host.app?.isGenerating === 'function') {
                try {
                    await host.waitFor(() => !host.app.isGenerating(), {
                        timeoutMs: 30_000,
                        label: 'generation to settle',
                    });
                } catch (error) {
                    log(`[builder] generation did not settle: ${error?.message || error}`);
                }
            }
            mark('generation');
            cancelPendingSaves();
            // No drain wait: the failure path appends nothing, the restore below
            // is the last state change, and waiting on a timer here only parks
            // the response behind jsdom's pending background work.
            mark('settle');
        } finally {
            try {
                restoreChatState();
            } catch (error) {
                log(`[builder] could not restore chat state: ${error?.message || error}`);
            }
            resolveCapture = null;
            rejectCapture = null;
        }

        mark('restore');
        record.timings = {
            characters: marks.characters - marks.start,
            online: marks.online - marks.characters,
            select: marks.select - marks.online,
            chat: marks.chat - marks.select,
            chatReady: marks.chatReady - marks.chat,
            input: marks.input - marks.chatReady,
            assemble: marks.capture - marks.input,
            generation: marks.generation - marks.capture,
            drain: marks.settle - marks.generation,
            restore: marks.restore - marks.settle,
            total: marks.restore - startedAt,
        };
        log(`[builder] build phases (ms): ${Object.entries(record.timings)
            .map(([name, value]) => `${name}=${Math.round(value)}`)
            .join(' ')}`);

        return record;
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
