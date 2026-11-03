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
 * Typed failure for "the worker is gone / cannot start". The route can fall
 * back to the in-process builder instead of hanging on a dead channel.
 */
export class BuilderUnavailableError extends Error {
    constructor(message, options) {
        super(message, options);
        this.name = 'BuilderUnavailableError';
        this.code = 'BUILDER_UNAVAILABLE';
    }
}

/**
 * @param {object} options
 * @param {string} options.dataDir Directory with default-user/{characters,chats,worlds,settings.json,secrets.json}.
 * @param {string} [options.serverDir] SillyTavern checkout; defaults to the repo's SillyTavern/ inside the worker.
 * @param {(line: string) => void} [options.onLog]
 * @param {number} [options.readyTimeoutMs]
 * @param {URL} [options.workerUrl] Entry module; tests substitute a fake worker.
 * @returns {{ready: () => Promise<void>, build: Function, close: Function, isWorker: true}}
 */
export function createWorkerBuilder({
    dataDir,
    serverDir,
    onLog = () => {},
    readyTimeoutMs = DEFAULT_READY_TIMEOUT_MS,
    workerUrl = new URL('./worker-entry.mjs', import.meta.url),
}) {
    if (!dataDir) throw new Error('createWorkerBuilder: dataDir is required');

    let worker = null;
    let workerAlive = false;
    let readyPromise = null;
    let readyTimer = null;
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
        clearTimeout(readyTimer);
        readyTimer = null;
        if (error) rejectReady?.(error);
        else resolveReady?.();
    }

    /**
     * Drops a dead worker. The next build() spawns a fresh one; anything that
     * was waiting on the dead worker settles with a typed error instead of
     * hanging on a channel nobody reads.
     */
    function retire(current, error) {
        if (worker !== current) return; // exit from an already-replaced worker
        workerAlive = false;
        settleReady(error);
        failPending(error);
        worker = null;
        readyPromise = null;
        resolveReady = null;
        rejectReady = null;
        readySettled = false;
    }

    function spawn() {
        if (worker && workerAlive) return worker;

        readySettled = false;
        readyPromise = new Promise((resolve, reject) => {
            resolveReady = resolve;
            rejectReady = reject;
        });
        readyTimer = setTimeout(() => {
            settleReady(new BuilderUnavailableError(`prompt builder worker was not ready within ${readyTimeoutMs}ms`));
        }, readyTimeoutMs);
        readyTimer.unref?.();

        const current = new Worker(workerUrl, {
            workerData: { dataDir, serverDir },
            // Builder output is forwarded through postMessage/onLog; nothing
            // in the worker should write to the parent's console directly.
            stderr: false,
            stdout: false,
        });
        worker = current;
        workerAlive = true;

        current.on('message', message => {
            switch (message?.type) {
                case 'log':
                    onLog(message.line);
                    break;
                case 'ready':
                    settleReady(null);
                    break;
                case 'fatal':
                    settleReady(new BuilderUnavailableError(String(message.message)));
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

        current.on('error', error => {
            retire(current, error instanceof BuilderUnavailableError
                ? error
                : new BuilderUnavailableError(`prompt builder worker failed: ${error?.message || error}`, { cause: error }));
        });

        current.on('exit', code => {
            retire(current, new BuilderUnavailableError(`prompt builder worker exited (${code})`));
        });

        return current;
    }

    function ready() {
        if (closed) return Promise.reject(new BuilderUnavailableError('prompt builder worker is closed'));
        spawn();
        return readyPromise;
    }

    function build(options) {
        const run = queue.then(async () => {
            if (closed) throw new BuilderUnavailableError('prompt builder worker is closed');
            const current = spawn();
            await readyPromise;
            if (!workerAlive || worker !== current || current.threadId === -1) {
                throw new BuilderUnavailableError('prompt builder worker exited before the build started');
            }

            const id = ++seq;
            const result = new Promise((resolve, reject) => {
                pending.set(id, { resolve, reject });
            });
            try {
                current.postMessage({ type: 'build', id, options });
            } catch (error) {
                pending.delete(id);
                throw new BuilderUnavailableError(`could not reach the prompt builder worker: ${error?.message || error}`, { cause: error });
            }
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
        const alive = workerAlive;
        worker = null;
        workerAlive = false;
        if (!current || !alive) return;
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
