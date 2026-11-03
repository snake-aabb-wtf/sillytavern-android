/**
 * R5 latency probe for POST /api/native/chat.
 *
 * Boots ONE builder (cold, in the worker thread the route uses in production),
 * then serves the real router with a fake provider and measures, per request,
 * the time from request start to the first forwarded byte. This is the number
 * R5 caps at 3000 ms, and for the warm requests the probe FAILS the run above
 * it. It also samples a health endpoint while each build is in flight: the
 * worker exists so a build cannot stall the server's event loop.
 *
 * Requires the parity harness dependencies (SillyTavern npm ci + native npm ci
 * with jsdom). It is not part of `node --test`; CI runs it after the parity
 * job's installs:
 *
 *   cd native && node test/latency-native-chat.mjs
 */
import fs from 'node:fs';
import http from 'node:http';

import { CASES, CHAT_FILE, USER_INPUT, prepareDataRoot, getFreePort } from './capture-reference.mjs';
import { createWorkerBuilder } from '../prompt-builder/worker-client.mjs';
import { createNativeChatRouter } from '../endpoints/native-chat.mjs';

// The builder replaces fetch inside the worker, not here, but keep the real
// implementation captured for the probe's own HTTP calls regardless.
const realFetch = globalThis.fetch.bind(globalThis);

const CHUNKS = [
    'data: {"choices":[{"delta":{"content":"Are "}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"apples"}}]}\n\n',
    'data: [DONE]\n\n',
];
// Request 1 pays the cold build; the R5 gate uses the warm requests after it.
// Five warm samples and a median: three samples left the decision sitting on a
// single runner hiccup, 11% under a 3000 ms budget.
const REQUESTS = Number(process.env.LATENCY_REQUESTS ?? 6);
const BUDGET_MS = 3000;
const HEALTH_BUDGET_MS = 200;
const WATCHDOG_MS = Number(process.env.LATENCY_WATCHDOG_MS ?? 600_000);

// CI loses buffered stdout when a step is cancelled; milestones go straight to
// stderr so a hang is diagnosable from the job log.
const trace = message => process.stderr.write(`[latency-trace] ${message}\n`);

function listen(handler) {
    return new Promise((resolve, reject) => {
        const server = http.createServer(handler);
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => resolve(server));
    });
}

async function main() {
    const watchdog = setTimeout(() => {
        trace('watchdog fired; see the last [builder]/[latency-trace] line above');
        process.exit(3);
    }, WATCHDOG_MS);

    const port = await getFreePort();
    const fixture = prepareDataRoot({ ...CASES[0], port });
    trace(`fixture ready at ${fixture.dataRoot}`);

    const coldStart = Date.now();
    const builder = createWorkerBuilder({
        dataDir: fixture.dataRoot,
        onLog: line => trace(`builder: ${line}`),
    });
    await builder.ready();
    const coldMs = Date.now() - coldStart;
    trace(`builder worker ready in ${coldMs}ms`);
    console.log(`[latency] cold_builder_ms=${coldMs}`);

    const provider = await listen((req, res) => {
        req.resume();
        req.on('end', () => {
            res.writeHead(200, { 'content-type': 'text/event-stream' });
            let index = 0;
            const pump = () => {
                if (index === CHUNKS.length) { res.end(); return; }
                res.write(CHUNKS[index++]);
                setTimeout(pump, 5);
            };
            pump();
        });
    });
    const providerUrl = `http://127.0.0.1:${provider.address().port}/generate`;

    const buildTimes = [];
    const buildPhases = [];
    const timedBuilder = {
        async build(options) {
            const started = Date.now();
            const result = await builder.build(options);
            buildTimes.push(Date.now() - started);
            if (result?.timings) buildPhases.push(result.timings);
            return result;
        },
    };

    const router = createNativeChatRouter({
        builder: timedBuilder,
        // Same shape as createDefaultForward, pointed at the fake provider.
        forward: ({ body, signal }) => realFetch(providerUrl, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify(body),
            signal,
        }),
        log: message => console.log(message),
    });

    const app = await listen((req, res) => {
        if (req.method === 'GET' && req.url === '/health') {
            res.writeHead(200, { 'content-type': 'text/plain' });
            res.end('ok');
            return;
        }
        req.url = req.url.replace(/^\/api\/native/, '') || '/';
        router(req, res, () => { res.statusCode = 404; res.end(); });
    });
    const endpoint = `http://127.0.0.1:${app.address().port}/api/native/chat`;
    const healthEndpoint = `http://127.0.0.1:${app.address().port}/health`;

    // Samples the server's event loop while a build is in flight. With the
    // builder on a worker this stays in the low milliseconds; on the old
    // in-process builder every sample would take as long as the build.
    const healthSamples = [];
    let healthSampling = false;
    const sampleHealth = async () => {
        if (!healthSampling) return;
        const started = Date.now();
        try {
            const response = await realFetch(healthEndpoint);
            await response.arrayBuffer();
            healthSamples.push(Date.now() - started);
        } catch {
            healthSamples.push(Infinity);
        }
        if (healthSampling) setTimeout(sampleHealth, 100);
    };
    const startHealthSampling = () => {
        healthSampling = true;
        sampleHealth();
    };
    const stopHealthSampling = () => {
        healthSampling = false;
    };

    const firstBytes = [];
    const totals = [];
    let failures = 0;

    try {
        for (let attempt = 1; attempt <= REQUESTS; attempt++) {
            const started = Date.now();
            startHealthSampling();
            const response = await realFetch(endpoint, {
                method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({
                    avatar_url: fixture.avatarUrl,
                    file_name: CHAT_FILE,
                    user_input: USER_INPUT,
                    type: 'send',
                }),
            });
            if (response.status !== 200) {
                failures++;
                stopHealthSampling();
                console.log(`[latency] request ${attempt} status=${response.status} ${await response.text()}`);
                continue;
            }
            const reader = response.body.getReader();
            let firstByteAt = null;
            const parts = [];
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                if (firstByteAt === null) firstByteAt = Date.now();
                parts.push(Buffer.from(value));
            }
            const totalMs = Date.now() - started;
            const firstByteMs = firstByteAt - started;
            const body = Buffer.concat(parts).toString('utf8');
            stopHealthSampling();
            const ok = body.trimEnd().endsWith('data: [DONE]');
            if (!ok) failures++;
            firstBytes.push(firstByteMs);
            totals.push(totalMs);
            const phases = buildPhases.at(-1);
            const phaseText = phases
                ? Object.entries(phases).map(([name, value]) => `${name}=${Math.round(value)}`).join(' ')
                : 'n/a';
            console.log(`[latency] request=${attempt} first_byte_ms=${firstByteMs} total_ms=${totalMs} build_ms=${buildTimes.at(-1)} done=${ok}`);
            console.log(`[latency] request=${attempt} build_phases_ms ${phaseText}`);
            // Let the client finish its post-stream bookkeeping before the next
            // message, like a user reading the reply would.
            await new Promise(resolve => setTimeout(resolve, 250));
        }
    } finally {
        await new Promise(resolve => {
            app.closeAllConnections?.();
            app.close(resolve);
        });
        await new Promise(resolve => {
            provider.closeAllConnections?.();
            provider.close(resolve);
        });
        trace('http servers closed');
        await builder.close();
        trace('builder closed');
        fs.rmSync(fixture.tmpRoot, { recursive: true, force: true });
        clearTimeout(watchdog);
    }

    const warm = firstBytes.slice(1);
    const median = values => {
        if (values.length === 0) return null;
        const sorted = [...values].sort((a, b) => a - b);
        return sorted[Math.floor(sorted.length / 2)];
    };
    const percentile = (values, fraction) => {
        if (values.length === 0) return null;
        const sorted = [...values].sort((a, b) => a - b);
        return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))];
    };
    const warmMedian = median(warm);
    const healthP95 = percentile(healthSamples, 0.95);
    const healthMax = healthSamples.length > 0 ? Math.max(...healthSamples) : null;
    const warmPhases = buildPhases.slice(1);

    console.log(`[latency] cold_builder_ms=${coldMs} first_request_first_byte_ms=${firstBytes[0] ?? 'n/a'} warm_median_first_byte_ms=${warmMedian ?? 'n/a'} warm_samples=${warm.length} requests=${firstBytes.length} failures=${failures}`);
    console.log(`[latency] health samples=${healthSamples.length} p95_ms=${healthP95 ?? 'n/a'} max_ms=${healthMax ?? 'n/a'} budget_ms=${HEALTH_BUDGET_MS}`);

    if (failures > 0) process.exitCode = 1;

    if (warmMedian === null || warmMedian > BUDGET_MS) {
        // Never relax the budget: fail with the phase profile attached so the
        // next reader can see where the time went.
        console.log(`[latency] FAIL: warm first byte ${warmMedian ?? 'n/a'}ms is above the R5 ${BUDGET_MS}ms budget`);
        for (const [index, phases] of warmPhases.entries()) {
            const text = Object.entries(phases).map(([name, value]) => `${name}=${Math.round(value)}`).join(' ');
            console.log(`[latency] warm request ${index + 2} build_phases_ms ${text}`);
        }
        process.exitCode = 1;
    } else {
        console.log(`[latency] PASS: warm first byte ${warmMedian}ms is within the R5 ${BUDGET_MS}ms budget`);
    }

    if (healthP95 !== null && healthP95 > HEALTH_BUDGET_MS) {
        console.log(`[latency] FAIL: health p95 ${healthP95}ms is above the ${HEALTH_BUDGET_MS}ms budget while building`);
        process.exitCode = 1;
    }
}

try {
    await main();
} catch (error) {
    trace(`failed: ${error?.stack || error}`);
    process.exit(1);
}
// The builder and jsdom leave timers/handles behind; do not wait for them.
process.exit(process.exitCode ?? 0);
