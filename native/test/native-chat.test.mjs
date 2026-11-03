/**
 * Unit tests for the native chat endpoint.
 *
 * No npm install required: the router is a plain Node request handler (the same
 * callable Express accepts via app.use), the builder is faked, and the provider
 * forwarder is faked. Run with:
 *
 *   node --test native/test/native-chat.test.mjs
 */
import assert from 'node:assert/strict';
import http from 'node:http';
import test from 'node:test';

import { createNativeChatRouter } from '../endpoints/native-chat.mjs';

const AVATAR = 'Fixture Plain.png';
const CHAT = 'fixture-chat';
const INPUT = 'Are apples always red?';

/** Three SSE chunks, in the order the client must receive them. */
const CHUNKS = [
    'data: {"choices":[{"delta":{"content":"Are "}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"apples"}}]}\n\n',
    'data: [DONE]\n\n',
];

/**
 * Starts a loopback server that mounts the router at /api/native the way
 * Express does (the mount prefix is stripped from req.url).
 */
async function listen(handler) {
    const server = http.createServer((req, res) => {
        const url = req.url ?? '/';
        req.url = url.startsWith('/api/native') ? url.slice('/api/native'.length) || '/' : url;
        handler(req, res, () => {
            res.statusCode = 404;
            res.end('not found');
        });
    });
    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    const { port } = server.address();
    return {
        url: `http://127.0.0.1:${port}/api/native/chat`,
        close: () => new Promise(resolve => server.close(resolve)),
    };
}

function fakeBuilder(calls, result = { url: '/api/backends/chat-completions/generate', body: { model: 'x', messages: [] } }) {
    return {
        async build(options) {
            calls.push(options);
            return result;
        },
    };
}

function sseForward(seen) {
    return async request => {
        seen.push(request);
        return {
            status: 200,
            headers: { 'content-type': 'text/event-stream' },
            body: (async function* stream() {
                for (const chunk of CHUNKS) yield Buffer.from(chunk);
            })(),
        };
    };
}

test('streams the provider chunks as text/event-stream and ends with [DONE]', async () => {
    const builds = [];
    const forwards = [];
    const app = await listen(createNativeChatRouter({
        builder: fakeBuilder(builds),
        forward: sseForward(forwards),
    }));

    try {
        const response = await fetch(app.url, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
                avatar_url: AVATAR,
                file_name: CHAT,
                user_input: INPUT,
                type: 'send',
            }),
        });

        assert.equal(response.status, 200);
        assert.match(response.headers.get('content-type') ?? '', /text\/event-stream/);

        const body = await response.text();
        const positions = CHUNKS.map(chunk => body.indexOf(chunk));
        assert.ok(positions.every(position => position >= 0), `missing chunk in ${JSON.stringify(body)}`);
        assert.deepEqual([...positions].sort((a, b) => a - b), positions, 'chunks arrived out of order');
        assert.ok(body.trimEnd().endsWith('data: [DONE]'), `body does not end with [DONE]: ${JSON.stringify(body)}`);

        // The builder is re-injected with the request's chat state, mapped to
        // the builder's own option names.
        assert.deepEqual(builds, [{
            avatarUrl: AVATAR,
            fileName: CHAT,
            userInput: INPUT,
            type: 'normal',
        }]);
        // And forward receives the exact url/body the builder produced.
        assert.equal(forwards.length, 1);
        assert.equal(forwards[0].url, '/api/backends/chat-completions/generate');
        assert.deepEqual(forwards[0].body, { model: 'x', messages: [] });
    } finally {
        await app.close();
    }
});

test('returns 400 JSON when user_input is empty', async () => {
    const builds = [];
    const app = await listen(createNativeChatRouter({
        builder: fakeBuilder(builds),
        forward: () => assert.fail('forward must not run for an invalid body'),
    }));

    try {
        const response = await fetch(app.url, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
                avatar_url: AVATAR,
                file_name: CHAT,
                user_input: '   ',
                type: 'send',
            }),
        });

        assert.equal(response.status, 400);
        assert.match(response.headers.get('content-type') ?? '', /application\/json/);
        const payload = await response.json();
        assert.match(payload.error, /user_input/);
        assert.equal(builds.length, 0);
    } finally {
        await app.close();
    }
});

test('aborts the upstream when the client disconnects', async () => {
    let aborted = false;
    const app = await listen(createNativeChatRouter({
        builder: fakeBuilder([]),
        forward: ({ signal }) => {
            signal.addEventListener('abort', () => { aborted = true; }, { once: true });
            return Promise.resolve({
                status: 200,
                headers: { 'content-type': 'text/event-stream' },
                body: (async function* stream() {
                    yield Buffer.from(CHUNKS[0]);
                    // Never finishes on its own: only the client abort ends it.
                    await new Promise((resolve, reject) => {
                        const timer = setTimeout(resolve, 5_000);
                        signal.addEventListener('abort', () => {
                            clearTimeout(timer);
                            const error = new Error('aborted');
                            error.name = 'AbortError';
                            reject(error);
                        }, { once: true });
                    });
                })(),
            });
        },
    }));

    const controller = new AbortController();
    try {
        const response = await fetch(app.url, {
            method: 'POST',
            signal: controller.signal,
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ avatar_url: AVATAR, file_name: CHAT, user_input: INPUT, type: 'send' }),
        });
        const reader = response.body.getReader();
        const first = await reader.read();
        assert.ok(first.value?.length > 0);
        controller.abort();
        await reader.cancel().catch(() => {});
        for (let i = 0; i < 50 && !aborted; i++) {
            await new Promise(resolve => setTimeout(resolve, 20));
        }
        assert.equal(aborted, true, 'forward signal was not aborted after the client left');
    } finally {
        await app.close();
    }
});
