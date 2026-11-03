/**
 * POST /api/native/chat — streaming bridge between the native client and the
 * existing provider dispatch.
 *
 * The native app sends one chat message; the route re-injects that chat state
 * into a single warm headless builder (see ../prompt-builder/index.mjs), gets
 * the exact /generate body the web client would send, forwards it to the same
 * server's provider route, and passes the SSE bytes straight through.
 *
 * R5: one builder per server lifetime. Building a body still means driving the
 * real client (select character, load chat, send), but the expensive part —
 * booting the server and the jsdom host — happens once at start-up. Builds are
 * serialized because the client is a process-wide singleton.
 *
 * The module is framework-neutral: the returned value is a plain
 * (req, res, next) handler, which Express accepts in app.use(). It reads
 * req.body when a body parser already ran and falls back to the request stream
 * otherwise, so the same code is testable with node:http.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

// The prompt builder installs its jsdom fetch wrapper on globalThis, and that
// wrapper short-circuits /api/backends/chat-completions/generate. Capture the
// real implementation while this module loads (before the builder is imported)
// so the loopback forward reaches the provider route instead of the intercept.
const NATIVE_FETCH = globalThis.fetch.bind(globalThis);

const MOUNT_PATH = '/chat';
const MAX_BODY_BYTES = 1024 * 1024;
const AUTH_HEADERS = ['cookie', 'x-csrf-token', 'authorization'];
const TYPE_MAP = { send: 'normal', regenerate: 'regenerate', continue: 'continue' };
const VALID_TYPES = new Set(Object.keys(TYPE_MAP));

/* ------------------------------------------------------------------ */
/* Small helpers                                                       */
/* ------------------------------------------------------------------ */

function sendJson(res, status, payload) {
    if (res.headersSent) {
        res.end();
        return;
    }
    const body = JSON.stringify(payload);
    res.statusCode = status;
    res.setHeader('content-type', 'application/json; charset=utf-8');
    res.setHeader('content-length', Buffer.byteLength(body));
    res.end(body);
}

function headerValue(headers, name) {
    if (!headers) return undefined;
    if (typeof headers.get === 'function') return headers.get(name) ?? undefined;
    return headers[name] ?? headers[name.toLowerCase()];
}

async function readBody(req) {
    // express.json() runs before this mount in server-main, so the common path
    // is already-parsed req.body. Fall back to the raw stream for tests.
    if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) {
        return req.body;
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) throw Object.assign(new Error('request body too large'), { statusCode: 413 });
        chunks.push(chunk);
    }
    if (chunks.length === 0) return {};
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function asString(value) {
    return typeof value === 'string' ? value.trim() : '';
}

/**
 * Yields chunks from either a web ReadableStream or an async iterable / Node
 * Readable. Keeping both shapes means the router works with fetch() responses
 * and with injected test doubles without converting streams.
 */
async function* iterateBody(body) {
    if (!body) return;
    if (typeof body.getReader === 'function') {
        const reader = body.getReader();
        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) return;
                if (value !== undefined) yield value;
            }
        } finally {
            try {
                await reader.cancel();
            } catch {
                // Already closed or aborted; nothing to release.
            }
        }
    } else {
        for await (const chunk of body) {
            yield chunk;
        }
    }
}

const MAX_ERROR_BODY_BYTES = 64 * 1024;

/** Collects a bounded upstream body so an error can be relayed verbatim. */
async function collectBody(body, limit = MAX_ERROR_BODY_BYTES) {
    const chunks = [];
    let size = 0;
    for await (const chunk of iterateBody(body)) {
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        chunks.push(buffer);
        size += buffer.length;
        if (size >= limit) break;
    }
    return Buffer.concat(chunks);
}

/**
 * Keeps the last few bytes already written to the client, so a terminal frame
 * can be separated from a partial upstream line (SSE chunks are arbitrary byte
 * boundaries, not event boundaries).
 */
function tailOf(previous, chunk) {
    const combined = previous + chunk.toString('utf8');
    return combined.length > 4 ? combined.slice(-4) : combined;
}

/**
 * Builds the terminal `event: error` frame, prefixing exactly enough newlines
 * to land on a fresh event boundary. Without this a mid-line upstream failure
 * corrupts the very line it is meant to report.
 */
function errorFrame({ wroteBytes, tail, message }) {
    const payload = JSON.stringify({ error: message });
    const frame = `event: error\ndata: ${payload}\n\n`;
    if (!wroteBytes) return frame;
    if (tail.endsWith('\n\n')) return frame;
    if (tail.endsWith('\n')) return `\n${frame}`;
    return `\n\n${frame}`;
}

/* ------------------------------------------------------------------ */
/* Router                                                              */
/* ------------------------------------------------------------------ */

/**
 * @param {object} options
 * @param {{build: Function}} options.builder Warm prompt builder.
 * @param {Function} options.forward ({ url, body, headers, signal }) =>
 *   Promise<{ status, headers, body }>, usually a fetch() Response.
 * @param {Function} [options.log]
 * @returns {(req, res, next?) => Promise<void>}
 */
export function createNativeChatRouter({ builder, forward, log = console.info }) {
    if (!builder || typeof builder.build !== 'function') throw new TypeError('createNativeChatRouter: builder.build is required');
    if (typeof forward !== 'function') throw new TypeError('createNativeChatRouter: forward is required');

    // The client is one process-wide singleton: two builds at once would
    // interleave character/chat state. Queue them; each request gets its own
    // result and its own abort signal.
    let queue = Promise.resolve();
    const serialize = task => {
        const run = queue.then(task, task);
        queue = run.then(() => {}, () => {});
        return run;
    };

    return async function nativeChatRouter(req, res, next) {
        const pathname = String(req.url ?? '/').split('?')[0];
        if (pathname !== MOUNT_PATH && pathname !== `${MOUNT_PATH}/`) {
            if (typeof next === 'function') { next(); return; }
            sendJson(res, 404, { error: 'not found' });
            return;
        }
        if (req.method !== 'POST') {
            res.setHeader('allow', 'POST');
            sendJson(res, 405, { error: 'method not allowed' });
            return;
        }

        let payload;
        try {
            payload = await readBody(req);
        } catch (error) {
            sendJson(res, error?.statusCode ?? 400, { error: `invalid JSON body: ${error?.message || error}` });
            return;
        }

        const avatarUrl = asString(payload?.avatar_url);
        const fileName = asString(payload?.file_name);
        const userInput = asString(payload?.user_input);
        const type = payload?.type === undefined ? 'send' : String(payload.type);

        if (!userInput) { sendJson(res, 400, { error: 'user_input is required' }); return; }
        if (!avatarUrl) { sendJson(res, 400, { error: 'avatar_url is required' }); return; }
        if (!fileName) { sendJson(res, 400, { error: 'file_name is required' }); return; }
        if (!VALID_TYPES.has(type)) {
            sendJson(res, 400, { error: `type must be one of ${[...VALID_TYPES].join(', ')}` });
            return;
        }

        const startedAt = Date.now();
        const controller = new AbortController();
        res.on('close', () => {
            // 'close' also fires after a normal end; only a premature close is
            // a client disconnect.
            if (!res.writableEnded) controller.abort();
        });

        let built;
        try {
            built = await serialize(() => {
                if (controller.signal.aborted) return { aborted: true };
                return builder.build({
                    avatarUrl,
                    fileName,
                    userInput,
                    type: TYPE_MAP[type],
                });
            });
        } catch (error) {
            const message = String(error?.message || error);
            const notImplemented = /unsupported type/i.test(message);
            log(`[native] build failed after ${Date.now() - startedAt}ms: ${message}`);
            sendJson(res, notImplemented ? 501 : 500, { error: `prompt build failed: ${message}` });
            return;
        }

        const builtAt = Date.now();
        if (controller.signal.aborted || built?.aborted) return;

        const headers = {};
        for (const name of AUTH_HEADERS) {
            const value = req.headers?.[name];
            if (value !== undefined) headers[name] = value;
        }

        let upstream;
        try {
            // The route only speaks SSE. The builder always asks for a stream,
            // but enforce it here so a body change cannot turn the response
            // into a single JSON object that the native client would wait on.
            const forwardBody = built.body && typeof built.body === 'object'
                ? { ...built.body, stream: true }
                : built.body;
            upstream = await forward({
                url: built.url,
                body: forwardBody,
                headers,
                signal: controller.signal,
            });
        } catch (error) {
            if (controller.signal.aborted) return;
            log(`[native] provider forward failed after ${Date.now() - startedAt}ms: ${error?.message || error}`);
            sendJson(res, 502, { error: `provider request failed: ${error?.message || error}` });
            return;
        }

        const contentType = headerValue(upstream.headers, 'content-type') ?? '';
        if (!/text\/event-stream/i.test(contentType)) {
            // Never pass a JSON error off as a stream: the client would see a
            // clean 200 with no [DONE]. Relay the upstream body verbatim, but
            // with a failure status and its real content-type.
            let body = Buffer.alloc(0);
            try {
                body = await collectBody(upstream.body);
            } catch {
                // An unreadable error body does not change the verdict.
            }
            log(`[native] provider returned non-SSE ${JSON.stringify(contentType)} after ${Date.now() - startedAt}ms; answering 502`);
            res.statusCode = 502;
            res.setHeader('content-type', contentType || 'application/json; charset=utf-8');
            res.setHeader('x-upstream-status', String(upstream.status ?? 0));
            res.end(body);
            return;
        }

        res.statusCode = upstream.status ?? 200;
        res.setHeader('content-type', contentType);
        res.setHeader('cache-control', 'no-cache, no-transform');
        res.setHeader('x-accel-buffering', 'no');
        if (typeof res.flushHeaders === 'function') res.flushHeaders();

        let firstChunkAt = null;
        let streamFailure = null;
        // Track what the client has actually received so a terminal frame can
        // never be glued onto a partial `data:` line. Upstream chunks are
        // arbitrary byte boundaries, not event boundaries.
        let wroteBytes = false;
        let tail = '';
        try {
            for await (const chunk of iterateBody(upstream.body)) {
                if (controller.signal.aborted) break;
                if (firstChunkAt === null) {
                    firstChunkAt = Date.now();
                    log(`[native] chat first byte in ${firstChunkAt - startedAt}ms (build ${builtAt - startedAt}ms)`);
                }
                wroteBytes = true;
                tail = tailOf(tail, Buffer.from(chunk));
                if (!res.write(chunk)) {
                    // Respect backpressure; also wake up if the client leaves.
                    await new Promise(resolve => {
                        const done = () => {
                            res.off('drain', done);
                            res.off('close', done);
                            resolve();
                        };
                        res.once('drain', done);
                        res.once('close', done);
                    });
                }
            }
        } catch (error) {
            if (!controller.signal.aborted) {
                streamFailure = String(error?.message || error);
                log(`[native] stream failed after ${Date.now() - startedAt}ms: ${streamFailure}`);
                // Headers are already out. A terminal SSE error frame is the
                // only in-band signal left; without it a truncated stream looks
                // like a completed reply.
                if (!res.writableEnded) {
                    try {
                        res.write(errorFrame({ wroteBytes, tail, message: `upstream stream failed: ${streamFailure}` }));
                    } catch {
                        // Client already gone.
                    }
                }
            }
        } finally {
            if (!res.writableEnded) res.end();
        }
    };
}

/* ------------------------------------------------------------------ */
/* Default wiring for the staged server                                */
/* ------------------------------------------------------------------ */

let defaultBuilderPromise = null;

/**
 * One builder for the process. serverDir is the staged runtime root that owns
 * this file (native/endpoints -> ../..). Lazy so importing the module in a
 * unit test never spawns a server.
 *
 * The builder runs in a worker thread: assembling a prompt drives jsdom
 * synchronously for a second or more, and on the main thread that would stall
 * every other request (health checks, static files, a concurrent provider
 * forward). The route only exchanges messages with the worker; SSE forwarding
 * stays here. NATIVE_CHAT_BUILDER=inline restores the in-process builder for
 * diagnosis.
 */
export function getDefaultBuilder() {
    if (!defaultBuilderPromise) {
        defaultBuilderPromise = (async () => {
            const dataDir = globalThis.DATA_ROOT;
            if (!dataDir) throw new Error('globalThis.DATA_ROOT is not set; cannot start the prompt builder');
            const serverDir = path.resolve(HERE, '../..');
            const onLog = line => console.info(`[native-builder] ${line}`);

            if (process.env.NATIVE_CHAT_BUILDER === 'inline') {
                const { createBuilder } = await import('../prompt-builder/index.mjs');
                return createBuilder({ dataDir, serverDir, onLog });
            }

            try {
                const { createWorkerBuilder } = await import('../prompt-builder/worker-client.mjs');
                const builder = createWorkerBuilder({ dataDir, serverDir, onLog });
                await builder.ready();
                return builder;
            } catch (error) {
                // A runtime without usable worker_threads still gets a working
                // route; it only loses event-loop isolation between turns.
                console.warn(`[native] prompt builder worker unavailable (${error?.message || error}); using the in-process builder`);
                const { createBuilder } = await import('../prompt-builder/index.mjs');
                return createBuilder({ dataDir, serverDir, onLog });
            }
        })();
        defaultBuilderPromise.catch(() => { defaultBuilderPromise = null; });
    }
    return defaultBuilderPromise;
}

/**
 * Builds with one recovery step for a worker that died.
 *
 * worker-client respawns on the next build, so a single retry covers a crash
 * between turns. Only when a fresh worker also fails does the route fall back
 * to the in-process builder (typed `BUILDER_UNAVAILABLE`, never a hang).
 */
let inlineBuilderPromise = null;

async function buildWithRecovery(options) {
    let lastError = null;
    for (let attempt = 0; attempt < 2; attempt++) {
        try {
            const builder = await getDefaultBuilder();
            return await builder.build(options);
        } catch (error) {
            if (error?.code !== 'BUILDER_UNAVAILABLE') throw error;
            lastError = error;
            console.warn(`[native] prompt builder worker unavailable (attempt ${attempt + 1}): ${error.message}`);
        }
    }

    if (!inlineBuilderPromise) {
        inlineBuilderPromise = (async () => {
            const dataDir = globalThis.DATA_ROOT;
            if (!dataDir) throw new Error('globalThis.DATA_ROOT is not set; cannot start the prompt builder');
            const serverDir = path.resolve(HERE, '../..');
            const { createBuilder } = await import('../prompt-builder/index.mjs');
            const builder = await createBuilder({
                dataDir,
                serverDir,
                onLog: line => console.info(`[native-builder] ${line}`),
            });
            defaultBuilderPromise = Promise.resolve(builder);
            return builder;
        })();
        inlineBuilderPromise.catch(() => {
            inlineBuilderPromise = null;
        });
    }
    const inline = await inlineBuilderPromise;
    try {
        return await inline.build(options);
    } catch (error) {
        throw new Error(`prompt builder unavailable: ${lastError?.message || 'worker lost'}; in-process fallback failed: ${error?.message || error}`);
    }
}

/**
 * Forwards the built body back into this same server's existing provider
 * route. provider dispatch (keys, presets, proxies, streaming format) is not
 * reimplemented here: the loopback request runs the normal Express handler.
 * The client's own session headers travel with it so auth/CSRF see the same
 * identity as the native request.
 */
export function createDefaultForward({ port = globalThis.COMMAND_LINE_ARGS?.port ?? 8000 } = {}) {
    return async ({ url, body, headers, signal }) => {
        // The builder records whatever URL the client used, which may be
        // absolute and carry the builder's own port. Only the path belongs to
        // this server: rebase it so provider dispatch always runs here.
        const recorded = new URL(url, 'http://127.0.0.1');
        const target = new URL(`${recorded.pathname}${recorded.search}`, `http://127.0.0.1:${port}`);
        return NATIVE_FETCH(target, {
            method: 'POST',
            headers: { 'content-type': 'application/json', ...headers },
            body: JSON.stringify(body),
            signal,
        });
    };
}

/**
 * Warms the builder as soon as the server answers. The mount is registered
 * before the server listens, so poll the loopback address briefly instead of
 * depending on startup internals.
 */
export function warmDefaultBuilder({ port = globalThis.COMMAND_LINE_ARGS?.port ?? 8000, log = console.info } = {}) {
    if (process.env.NATIVE_CHAT_WARMUP === '0') return;
    const deadline = Date.now() + 180_000;
    const tick = async () => {
        let listening = false;
        try {
            const response = await NATIVE_FETCH(`http://127.0.0.1:${port}/`, { method: 'GET' });
            listening = response.status < 500;
        } catch {
            // not listening yet
        }
        if (!listening) {
            if (Date.now() < deadline) setTimeout(tick, 500).unref?.();
            return;
        }
        try {
            const startedAt = Date.now();
            const builder = await getDefaultBuilder();
            // The worker builder resolves this itself; keep the call so a
            // future builder with a lazy boot still warms fully here.
            await builder.ready?.();
            log(`[native] prompt builder warm in ${Date.now() - startedAt}ms`);
        } catch (error) {
            // Do not retry-storm: the first request will try again and report
            // the same error to the client.
            console.warn(`[native] prompt builder warm-up failed: ${error?.message || error}`);
        }
    };
    setTimeout(tick, 500).unref?.();
}

const defaultRouter = createNativeChatRouter({
    builder: { build: options => buildWithRecovery(options) },
    forward: (request) => createDefaultForward()(request),
    log: message => console.info(message),
});

if (globalThis.DATA_ROOT) {
    warmDefaultBuilder();
}

export { defaultRouter as default };
