/**
 * worker-client must never wedge the route when the builder worker dies.
 *
 * A dead worker previously left `worker`/`readyPromise` pointing at a thread
 * nobody reads, so the next build() posted into the void and the request hung.
 * The contract tested here: a crash rejects the in-flight build with a typed
 * BUILDER_UNAVAILABLE error, and the next build() respawns and succeeds; a
 * worker that cannot boot fails fast with the same typed error.
 */
import assert from 'node:assert/strict';
import { createWorkerBuilder, BuilderUnavailableError } from '../prompt-builder/worker-client.mjs';

const FAKE = new URL('./fixtures/fake-worker.mjs', import.meta.url);
const DEAF = new URL('./fixtures/deaf-worker.mjs', import.meta.url);

const withTimeout = (promise, ms, label) => Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} did not settle within ${ms}ms`)), ms).unref?.()),
]);

const run = async () => {
    const builder = createWorkerBuilder({
        dataDir: '/tmp/worker-recovery-test',
        workerUrl: FAKE,
    });

    await withTimeout(builder.ready(), 5_000, 'first ready');

    const first = await withTimeout(builder.build({ n: 1 }), 5_000, 'first build');
    assert.equal(first.ok, true);
    assert.equal(first.options.n, 1);

    // Kill the worker mid-build: the caller must get a typed error, not a hang.
    await assert.rejects(
        withTimeout(builder.build({ n: 2, suicide: true }), 5_000, 'suicide build'),
        error => error instanceof BuilderUnavailableError && error.code === 'BUILDER_UNAVAILABLE',
    );

    // Next build: worker-client respawns on demand.
    const afterCrash = await withTimeout(builder.build({ n: 3 }), 10_000, 'build after crash');
    assert.equal(afterCrash.ok, true);
    assert.equal(afterCrash.options.n, 3);
    console.log('[worker-recovery] ok: mid-build crash rejected with BUILDER_UNAVAILABLE, next build respawned');

    await builder.close();

    // A worker that never reports ready must fail fast with the same type.
    const deaf = createWorkerBuilder({
        dataDir: '/tmp/worker-recovery-test',
        workerUrl: DEAF,
        readyTimeoutMs: 300,
    });
    const startedAt = Date.now();
    await assert.rejects(
        withTimeout(deaf.build({ n: 1 }), 5_000, 'deaf build'),
        error => error instanceof BuilderUnavailableError && error.code === 'BUILDER_UNAVAILABLE',
    );
    const elapsed = Date.now() - startedAt;
    assert.ok(elapsed < 4_000, `typed startup failure took ${elapsed}ms`);
    await deaf.close();
    console.log(`[worker-recovery] ok: silent worker failed fast in ${elapsed}ms with BUILDER_UNAVAILABLE`);
};

run().then(
    () => process.exit(0),
    error => {
        console.error(`[worker-recovery] failed: ${error?.stack || error}`);
        process.exit(1);
    },
);
