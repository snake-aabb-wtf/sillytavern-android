/**
 * Main-thread client for the prompt-builder worker (see worker-entry.mjs).
 *
 * Exposes the same surface as createBuilder(): ready(), build(options),
 * close(). Builds are serialized because the worker owns one jsdom client,
 * and every request keeps its own promise so callers never share a result.
 */
import { Worker } from 'node:worker_threads';

const DEFAULT_READY_TIMEOUT_MS = 300_000;

/**
 * @param {object} options
 * @param {string} options.dataDir Directory with default-user/{characters,chats,worlds,settings.json,secrets.json}.
 * @param {string} [options.serverDir] SillyTavern checkout; defaults to the repo's SillyTavern/ inside the worker.
 * @param {(line: string) => void} [options.onLog]
 * @param {number} [options.readyTimeoutMs]
 * @returns {{ready: () => Promise<void>, build: Function, close: Function, isWorker: true}}
 */
export function createWorkerBuilder({
    dataDir,
    serverDir,
    onLog = () => {},
    readyTimeoutMs = DEFAULT_READY_TIMEOUT_MS,
}) {
    if (!dataDir) throw new Error('createWorkerBuilder: dataDir is required');

    let worker = null;
    let readyPromise = null;
    let resolveReady = null;
    let rejectReady = null;
    let readySettled = false;
    let closed = false;
    let seq = 0;
    const pending = new Map();
    let queue = Promise.resolve();

    const failPending = error => {
        for (const entry of pending.values()) entry.reject(error);
        pending.clear();
    };

    function settleReady(error) {
        if (readySettled) return;
        readySettled = true;
        if (error) rejectReady?.(error);
        else resolveReady?.();
    }

    function spawn() {
        if (worker) return worker;

        readyPromise = new Promise((resolve, reject) => {
            resolveReady = resolve;
            rejectReady = reject;
        });
        const readyTimer = setTimeout(() => {
            settleReady(new Error(`prompt builder worker was not ready within ${readyTimeoutMs}ms`));
        }, readyTimeoutMs);
        readyTimer.unref?.();

        worker = new Worker(new URL('./worker-entry.mjs', import.meta.url), {
            workerData: { dataDir, serverDir },
            // Builder output is forwarded through postMessage/onLog; nothing
            // in the worker should write to the parent's console directly.
            stderr: false,
            stdout: false,
        });

        worker.on('message', message => {
            switch (message?.type) {
                case 'log':
                    onLog(message.line);
                    break;
                case 'ready':
                    clearTimeout(readyTimer);
                    settleReady(null);
                    break;
                case 'fatal':
                    clearTimeout(readyTimer);
                    settleReady(new Error(String(message.message)));
                    break;
                case 'result': {
                    const entry = pending.get(message.id);
                    if (entry) {
                        pending.delete(message.id);
                        entry.resolve(message.result);
                    }
                    break;
                }
                case 'error': {
                    const entry = pending.get(message.id);
                    if (entry) {
                        pending.delete(message.id);
                        entry.reject(new Error(String(message.message)));
                    }
                    break;
                }
                default:
                    break;
            }
        });

        worker.on('error', error => {
            clearTimeout(readyTimer);
            settleReady(error instanceof Error ? error : new Error(String(error)));
            failPending(error instanceof Error ? error : new Error(String(error)));
        });

        worker.on('exit', code => {
            clearTimeout(readyTimer);
            const error = new Error(`prompt builder worker exited (${code})`);
            settleReady(error);
            failPending(error);
        });

        return worker;
    }

    function ready() {
        if (closed) return Promise.reject(new Error('prompt builder worker is closed'));
        spawn();
        return readyPromise;
    }

    function build(options) {
        const run = queue.then(async () => {
            if (closed) throw new Error('prompt builder worker is closed');
            spawn();
            await readyPromise;

            const id = ++seq;
            const result = new Promise((resolve, reject) => {
                pending.set(id, { resolve, reject });
            });
            worker.postMessage({ type: 'build', id, options });
            return result;
        });
        // Keep the queue alive after a failed build, but let this caller see
        // the failure.
        queue = run.then(() => {}, () => {});
        return run;
    }

    async function close() {
        closed = true;
        await queue.catch(() => {});
        const current = worker;
        worker = null;
        if (!current) return;
        await new Promise(resolve => {
            const timer = setTimeout(() => {
                current.terminate().finally(resolve);
            }, 15_000);
            timer.unref?.();
            current.once('exit', () => {
                clearTimeout(timer);
                resolve();
            });
            try {
                current.postMessage({ type: 'close' });
            } catch {
                clearTimeout(timer);
                current.terminate().finally(resolve);
            }
        });
    }

    return { ready, build, close, isWorker: true };
}
