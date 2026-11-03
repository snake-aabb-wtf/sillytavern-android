/**
 * Stand-in prompt builder for worker-client tests: speaks the same message
 * protocol, boots instantly, and can be told to die mid-build.
 */
import { parentPort, workerData } from 'node:worker_threads';

parentPort.postMessage({ type: 'log', line: `fake worker booted for ${workerData?.dataDir}` });
parentPort.postMessage({ type: 'ready' });

parentPort.on('message', message => {
    if (message?.type === 'close') {
        process.exit(0);
    }
    if (message?.type === 'build') {
        const { id, options } = message;
        if (options?.suicide) {
            // Simulates a worker crash while the build is in flight.
            process.exit(7);
        }
        if (options?.neverReady === false) {
            // no-op
        }
        setTimeout(() => {
            parentPort.postMessage({ type: 'result', id, result: { ok: true, options } });
        }, 5);
    }
});
