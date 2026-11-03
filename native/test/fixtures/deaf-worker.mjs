/** Boots but never reports ready: exercises the typed startup timeout. */
import { parentPort } from 'node:worker_threads';

parentPort.on('message', () => {
    // Deliberately silent.
});
