/**
 * World-info continuity across the two-turn path.
 *
 * The optimised route adopts a character itself instead of running
 * selectCharacterById() -> getChat() -> getChatResult(). Those calls are what
 * used to establish `this_chid`/`name2`, and world-info activation reads both,
 * so a naive direct load can drop the character's lorebook from the prompt.
 * This test builds twice on the worldinfo fixture: the entry must be in turn 1
 * and still in turn 2 after the caller persists a reply, with no canned reply.
 *
 * Needs the parity environment (SillyTavern npm ci + native npm ci). Runs in
 * CI next to chat-state-isolation.mjs.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

import { CASES, CHAT_FILE, USER_INPUT, prepareDataRoot, getFreePort } from './capture-reference.mjs';
import { createBuilder } from '../prompt-builder/index.mjs';

const WATCHDOG_MS = Number(process.env.ISOLATION_WATCHDOG_MS ?? 600_000);
const SECOND_USER_INPUT = 'Does the apple lore say red?';
const PERSISTED_REPLY = 'The lore says apples are red.';
const LORE = 'APPLE FACT: apples are red.';
const trace = message => process.stderr.write(`[worldinfo] ${message}\n`);

const REAL_FETCH = globalThis.fetch.bind(globalThis);

process.on('unhandledRejection', error => {
    trace(`unhandled rejection: ${error?.stack || error}`);
});

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
    const fixture = prepareDataRoot({ ...CASES[1], port });
    const chatFile = path.join(fixture.dataRoot, 'default-user', 'chats', fixture.cardName, `${CHAT_FILE}.jsonl`);

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
            const send = () => REAL_FETCH(url(relative), {
                ...init,
                headers: {
                    cookie: jar.header(),
                    'x-csrf-token': token,
                    ...(init.body ? { 'content-type': 'application/json' } : {}),
                    ...init.headers,
                },
            });
            let response;
            try {
                response = await send();
            } catch (error) {
                // Same stale keep-alive race as chat-state-isolation.mjs: the
                // builder server may close an idle socket between turns.
                const code = error?.cause?.code ?? error?.code;
                trace(`retrying ${relative} after ${code ?? error?.message ?? error}`);
                response = await send();
            }
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
        const contents = messages => (messages ?? []).map(message => String(message.content ?? ''));
        assert.equal(
            contents(first.body.messages).some(content => content.includes(LORE)),
            true,
            `turn 1 lost the character lorebook: ${JSON.stringify(contents(first.body.messages)).slice(0, 900)}`,
        );
        trace(`turn 1 ${first.body.messages.length} messages with the lorebook entry`);

        // Persist a real assistant reply, then rebuild: turn 2 must keep both
        // the reply and the lorebook.
        const savedChat = fs.readFileSync(chatFile, 'utf8')
            .split('\n')
            .filter(Boolean)
            .map(line => JSON.parse(line));
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
        console.log(`[worldinfo] save ~ status=${saveResponse.status} body=${saveBody.slice(0, 120)}`);
        assert.equal(saveResponse.status, 200, `POST /api/chats/save failed (${saveResponse.status}): ${saveBody}`);

        const second = await builder.build({ ...options, userInput: SECOND_USER_INPUT });
        const secondContents = contents(second.body.messages);
        assert.equal(
            secondContents.some(content => content.includes(PERSISTED_REPLY)),
            true,
            'turn 2 did not carry the persisted reply',
        );
        assert.equal(
            secondContents.some(content => content.includes(LORE)),
            true,
            `turn 2 lost the character lorebook: ${JSON.stringify(secondContents).slice(0, 900)}`,
        );

        const canned = (second.body.messages ?? []).filter(message =>
            message.role === 'assistant' && String(message.content ?? '').trim() === 'ok');
        assert.equal(canned.length, 0, `the canned SSE reply leaked into turn 2: ${JSON.stringify(canned)}`);

        trace(`ok: turn 1 ${first.body.messages.length} messages, turn 2 ${second.body.messages.length} messages, lorebook entry in both`);
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
