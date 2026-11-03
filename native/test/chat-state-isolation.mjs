/**
 * Correctness test for the builder's chat-state isolation.
 *
 * The activation receives a canned SSE reply ("ok") instead of a provider
 * answer, and SillyTavern's normal post-generation path would append that reply
 * to the chat and save it. If that leaks, the second build replays "ok" as real
 * history. This test:
 *
 *   1. records the fixture chat file bytes,
 *   2. runs build() twice in the same process and chat,
 *   3. asserts both requests carry identical messages (no injected reply, and
 *      in particular no assistant "ok" message), and
 *   4. asserts the chat file on disk is byte-identical afterwards.
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
const trace = message => process.stderr.write(`[isolation] ${message}\n`);

async function main() {
    const watchdog = setTimeout(() => {
        trace('watchdog fired');
        process.exit(3);
    }, WATCHDOG_MS);

    const port = await getFreePort();
    const fixture = prepareDataRoot({ ...CASES[0], port });
    const chatFile = path.join(fixture.dataRoot, 'default-user', 'chats', fixture.cardName, `${CHAT_FILE}.jsonl`);
    const before = fs.readFileSync(chatFile);
    trace(`chat file: ${chatFile} (${before.length} bytes)`);

    const builder = await createBuilder({
        dataDir: fixture.dataRoot,
        onLog: line => trace(`builder: ${line}`),
    });

    try {
        const options = {
            avatarUrl: fixture.avatarUrl,
            fileName: CHAT_FILE,
            userInput: USER_INPUT,
            type: 'normal',
        };
        const first = await builder.build(options);
        trace(`first build: ${first.body.messages?.length} messages`);
        const second = await builder.build(options);
        trace(`second build: ${second.body.messages?.length} messages`);

        assert.deepEqual(
            second.body.messages,
            first.body.messages,
            'the second build sent a different history: the activation polluted the chat',
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
            after.equals(before),
            true,
            `build() modified the chat file on disk (${before.length} -> ${after.length} bytes)`,
        );

        console.log(`[isolation] ok: ${second.body.messages.length} messages identical across two builds; chat file unchanged (${after.length} bytes)`);
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
