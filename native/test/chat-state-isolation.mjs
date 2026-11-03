/**
 * Correctness test for the builder's chat-state isolation and turn continuity.
 *
 * The activation receives a canned SSE reply ("ok") instead of a provider
 * answer, and SillyTavern's normal post-generation path would append that reply
 * to the chat and save it. The builder must roll that back. It must also see
 * replies that a caller persisted between turns, even though one builder is
 * reused for the whole process.
 *
 * The test runs a real two-turn scenario against one builder:
 *
 *   1. records the fixture chat file bytes,
 *   2. build() #1 with the fixture prompt,
 *   3. appends a distinct assistant reply to the chat file through the real
 *      `POST /api/chats/save` endpoint (CSRF handshake included),
 *   4. build() #2 with a different prompt,
 *   5. asserts #2 carries the persisted reply, #2 differs from #1, no canned
 *      "ok" leaked into either, and #1's history does not already contain the
 *      reply, and
 *   6. asserts the chat file on disk still equals the saved file (build() did
 *      not write over it).
 *
 * Needs the parity environment (SillyTavern npm ci + native npm ci). Runs in
 * CI; not part of `node --test`.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

import { CASES, CHAT_FILE, USER_INPUT, prepareDataRoot, getFreePort } from './capture-reference.mjs';
import { createBuilder } from '../prompt-builder/index.mjs';

const WATCHDOG_MS = Number(process.env.ISOLATION_WATCHDOG_MS ?? 600_000);
const SECOND_USER_INPUT = 'And are green apples usually sour?';
const PERSISTED_REPLY = 'Not every apple is red. Green apples are usually sour.';
const trace = message => process.stderr.write(`[isolation] ${message}\n`);

// The client fires UI promises it never awaits (PromptManager re-renders after
// a CHAT_LOADED, settings saves, ...). Closing the builder can leave one in
// flight, and Node would abort the whole test on the rejection before the
// explicit exit below. Report it; the assertions decide the verdict.
process.on('unhandledRejection', error => {
    trace(`unhandled rejection: ${error?.stack || error}`);
});

// createBuilder() installs a jsdom fetch wrapper process-wide; keep the real
// one for the HTTP calls this test makes on its own behalf.
const REAL_FETCH = globalThis.fetch.bind(globalThis);

function createCookieJar() {
    const cookies = new Map();
    return {
        store(response) {
            const raw = typeof response.headers.getSetCookie === 'function'
                ? response.headers.getSetCookie()
                : [response.headers.get('set-cookie')].filter(Boolean);
            for (const entry of raw) {
                const pair = entry.split(';', 1)[0];
                const index = pair.indexOf('=');
                if (index > 0) cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
            }
        },
        header() {
            return [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');
        },
    };
}

async function main() {
    const watchdog = setTimeout(() => {
        trace('watchdog fired');
        process.exit(3);
    }, WATCHDOG_MS);

    const port = await getFreePort();
    const fixture = prepareDataRoot({ ...CASES[0], port });
    const chatFile = path.join(fixture.dataRoot, 'default-user', 'chats', fixture.cardName, `${CHAT_FILE}.jsonl`);
    const original = fs.readFileSync(chatFile);
    trace(`chat file: ${chatFile} (${original.length} bytes)`);

    const builder = await createBuilder({
        dataDir: fixture.dataRoot,
        onLog: line => trace(`builder: ${line}`),
    });

    try {
        const jar = createCookieJar();
        const url = relative => new URL(relative, builder.baseUrl);

        const tokenResponse = await REAL_FETCH(url('csrf-token'));
        jar.store(tokenResponse);
        const { token } = await tokenResponse.json();
        assert.ok(token, 'could not obtain a CSRF token from the builder server');

        const request = async (relative, init = {}) => {
            const response = await REAL_FETCH(url(relative), {
                ...init,
                headers: {
                    cookie: jar.header(),
                    'x-csrf-token': token,
                    ...(init.body ? { 'content-type': 'application/json' } : {}),
                    ...init.headers,
                },
            });
            jar.store(response);
            return response;
        };

        const options = {
            avatarUrl: fixture.avatarUrl,
            fileName: CHAT_FILE,
            userInput: USER_INPUT,
            type: 'normal',
        };
        const first = await builder.build(options);
        trace(`first build: ${first.body.messages?.length} messages`);

        // Turn 1 ends: the caller persists a real assistant reply, exactly the
        // way the app does after a provider response.
        const savedChat = fs.readFileSync(chatFile, 'utf8')
            .split('\n')
            .filter(Boolean)
            .map(line => JSON.parse(line));
        assert.ok(savedChat.length >= 2, 'the fixture chat should have a header and at least one message');
        savedChat.push({
            name: savedChat[0]?.character_name ?? '',
            is_user: false,
            is_system: false,
            send_date: Date.now(),
            mes: PERSISTED_REPLY,
            extra: {},
        });

        const saveResponse = await request('api/chats/save', {
            method: 'POST',
            body: JSON.stringify({
                ch_name: savedChat[0]?.character_name ?? '',
                file_name: CHAT_FILE,
                avatar_url: fixture.avatarUrl,
                chat: savedChat,
                force: true,
            }),
        });
        const saveBody = await saveResponse.text();
        assert.equal(saveResponse.status, 200, `POST /api/chats/save failed (${saveResponse.status}): ${saveBody}`);
        assert.match(saveBody, /"ok"\s*:\s*true/, `POST /api/chats/save did not confirm: ${saveBody}`);

        const afterSave = fs.readFileSync(chatFile);
        assert.equal(
            afterSave.includes(PERSISTED_REPLY),
            true,
            'the persisted reply is not in the chat file; the test setup is wrong',
        );
        trace(`persisted reply, chat file now ${afterSave.length} bytes`);

        const second = await builder.build({ ...options, userInput: SECOND_USER_INPUT });
        trace(`second build: ${second.body.messages?.length} messages`);

        const contents = messages => (messages ?? []).map(message => String(message.content ?? ''));
        assert.equal(
            contents(first.body.messages).some(content => content.includes(PERSISTED_REPLY)),
            false,
            'the first prompt already contained the reply that was persisted after it',
        );

        // This is the continuity requirement: turn 2 must read the chat from the
        // server, not reuse the restored in-memory array.
        assert.equal(
            contents(second.body.messages).some(content => content.includes(PERSISTED_REPLY)),
            true,
            `the second build did not carry the persisted reply: ${JSON.stringify(contents(second.body.messages))}`,
        );

        assert.notDeepEqual(
            second.body.messages,
            first.body.messages,
            'the second build sent the same history as the first: the persisted reply was not loaded',
        );

        const canned = (second.body.messages ?? []).filter(message =>
            message.role === 'assistant' && String(message.content ?? '').trim() === 'ok');
        assert.equal(
            canned.length,
            0,
            `the canned SSE reply leaked into the next prompt: ${JSON.stringify(canned)}`,
        );

        const after = fs.readFileSync(chatFile);
        assert.equal(
            after.equals(afterSave),
            true,
            `build() modified the chat file on disk (${afterSave.length} -> ${after.length} bytes)`,
        );

        // stderr: this script exits explicitly (jsdom keeps handles open) and
        // buffered stdout would be dropped by process.exit().
        trace(`ok: turn 1 ${first.body.messages.length} messages, turn 2 ${second.body.messages.length} messages with the persisted reply; chat file unchanged (${after.length} bytes)`);
    } finally {
        await builder.close();
        fs.rmSync(fixture.tmpRoot, { recursive: true, force: true });
        clearTimeout(watchdog);
    }
}

try {
    await main();
} catch (error) {
    trace(`failed: ${error?.stack || error}`);
    process.exit(1);
}
process.exit(process.exitCode ?? 0);
