/**
 * Worker-thread entry for the prompt builder.
 *
 * build() drives a real jsdom client, and jsdom's work is synchronous and
 * CPU-bound. On the server's main thread that stalls every other request
 * (health checks, static files, the provider forward of a concurrent turn) for
 * the whole build. The worker owns the client and the SillyTavern instance
 * behind it; the main thread only exchanges small messages:
 *
 *   main -> worker  { type: 'build', id, options }
 *   worker -> main  { type: 'ready' }
 *                   { type: 'result', id, result }
 *                   { type: 'error', id, message }
 *                   { type: 'log', line }
 *                   { type: 'close' } -> { type: 'closed' }
 *
 * Contract is unchanged: the worker returns the same { url, body } record the
 * in-process builder returns (plus the phase timings).
 */
import { parentPort, workerData } from 'node:worker_threads';

import { createBuilder } from './index.mjs';

if (!parentPort) {
    throw new Error('worker-entry.mjs must be started as a worker thread');
}

const { dataDir, serverDir } = workerData ?? {};
const send = message => {
    try {
        parentPort.postMessage(message);
    } catch {
        // The main thread is gone; nothing to deliver to.
    }
};
const log = line => send({ type: 'log', line: String(line) });

// Same reason as the CLI entry: the client fires UI promises it never awaits,
// and a rejected one must not take the worker (and the route) down.
process.on('unhandledRejection', error => {
    log(`[builder-worker] unhandled rejection: ${error?.stack || error}`);
});

let builderPromise = null;
function getBuilder() {
    if (!builderPromise) {
        builderPromise = createBuilder({ dataDir, serverDir, onLog: log });
        builderPromise.then(
            () => send({ type: 'ready' }),
            error => send({ type: 'fatal', message: String(error?.message || error) }),
        );
    }
    return builderPromise;
}

// Boot the client as soon as the worker starts, exactly like the in-process
// warm-up did: the first chat request must not pay the jsdom/server boot.
getBuilder();

parentPort.on('message', message => {
    if (!message || typeof message !== 'object') return;

    if (message.type === 'build') {
        const { id, options } = message;
        getBuilder()
            .then(builder => builder.build(options))
            .then(
                result => send({ type: 'result', id, result }),
                error => send({ type: 'error', id, message: String(error?.message || error) }),
            );
        return;
    }

    if (message.type === 'close') {
        Promise.resolve(builderPromise)
            .then(builder => builder?.close())
            .catch(error => log(`[builder-worker] close failed: ${error?.message || error}`))
            .finally(() => {
                send({ type: 'closed' });
                parentPort.close();
            });
    }
});
