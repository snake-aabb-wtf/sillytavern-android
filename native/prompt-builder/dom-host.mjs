/**
 * jsdom host for the real SillyTavern web client.
 *
 * Why this is not just "open index.html and wait for window.SillyTavern":
 * index.html loads the application as `<script type="module" src="script.js">`,
 * and jsdom does not implement module scripts. So the DOM (markup, jQuery,
 * toastr, polyfills) is created by jsdom, and the ES module entry point is
 * evaluated by Node itself against that DOM. Every browser global the modules
 * touch (document, Event, localStorage, fetch, ...) is installed on Node's
 * globalThis first, so the unmodified upstream code runs as it does in a tab.
 *
 * The result is the same application object graph the browser builds, with no
 * browser process involved.
 */
import fs from 'node:fs';
import path from 'node:path';
import util from 'node:util';
import { register } from 'node:module';
import { pathToFileURL } from 'node:url';

import { JSDOM, VirtualConsole } from 'jsdom';

/**
 * Browser globals that must come from the jsdom realm, not from Node, or the
 * page's `instanceof` checks and DOM calls would fail.
 */
const FORCED_GLOBALS = [
    'document',
    'navigator',
    'location',
    'history',
    'localStorage',
    'sessionStorage',
    'HTMLElement',
    'HTMLInputElement',
    'HTMLTextAreaElement',
    'HTMLSelectElement',
    'HTMLCanvasElement',
    'HTMLImageElement',
    'HTMLDivElement',
    'HTMLAnchorElement',
    'Element',
    'Node',
    'NodeList',
    'DocumentFragment',
    'Text',
    'Comment',
    'Event',
    'CustomEvent',
    'MouseEvent',
    'KeyboardEvent',
    'InputEvent',
    'UIEvent',
    'FocusEvent',
    'DOMParser',
    'XMLSerializer',
    'MutationObserver',
    'getComputedStyle',
    'requestAnimationFrame',
    'cancelAnimationFrame',
    'Image',
    'FileReader',
    'XMLHttpRequest',
    'CSS',
    'Range',
    'Selection',
    'DOMRect',
    'Audio',
    'console',
    'matchMedia',
    'ResizeObserver',
    'IntersectionObserver',
];

const LOG_LIMIT = 4000;

function formatArgs(args) {
    return args
        .map(arg => (typeof arg === 'string' ? arg : util.inspect(arg, { depth: 2, breakLength: 120 })))
        .join(' ');
}

function createVirtualConsole(log) {
    const virtualConsole = new VirtualConsole();
    for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
        virtualConsole.on(level, (...args) => log(`[page:${level}] ${formatArgs(args)}`));
    }
    virtualConsole.on('jsdomError', error => {
        log(`[page:jsdomError] ${error?.stack || error}`);
    });
    return virtualConsole;
}

function tailLogs(logs, count = 60) {
    return logs.slice(-count).join('\n');
}

/**
 * Adds the handful of browser APIs jsdom leaves out. Everything here is inert:
 * it exists so the upstream UI code can finish initialising, not to render.
 */
function installShims(window, log) {
    if (typeof window.matchMedia !== 'function') {
        window.matchMedia = query => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: () => {},
            removeListener: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            dispatchEvent: () => false,
        });
    }

    class NoopObserver {
        observe() {}
        unobserve() {}
        disconnect() {}
        takeRecords() { return []; }
    }
    window.ResizeObserver ??= NoopObserver;
    window.IntersectionObserver ??= NoopObserver;

    window.scrollTo ??= () => {};
    window.scrollBy ??= () => {};

    // jsdom does not implement the CSS namespace object; several modules call
    // CSS.supports() at import time and CSS.escape() when building selectors.
    if (!window.CSS) window.CSS = {};
    window.CSS.supports ??= () => false;
    window.CSS.escape ??= value => String(value)
        .replace(/[^a-zA-Z0-9_\u00A0-\uFFFF-]/g, character => `\\${character}`);

    // jsdom does not expose the CSSOM rule constructors; dynamic-styles.js
    // probes them with instanceof. Empty classes keep the probes false.
    for (const name of [
        'CSSRule', 'CSSStyleRule', 'CSSMediaRule', 'CSSSupportsRule', 'CSSKeyframesRule',
        'CSSImportRule', 'CSSFontFaceRule', 'CSSPageRule', 'CSSNamespaceRule',
        'CSSConditionRule', 'CSSGroupingRule',
    ]) {
        // jsdom exposes some of these as present-but-undefined, which breaks
        // `x instanceof CSSSupportsRule` with "RHS is not an object".
        if (typeof window[name] !== 'function') window[name] = class {};
    }

    // jsdom ignores <script type="module">, so extension activation would wait
    // forever for an onload that never fires. Fire it without executing the
    // extension bundle: extensions only add optional UI here, and the parity
    // capture shows they contribute nothing to the request body.
    const appendChild = window.Node.prototype.appendChild;
    const insertBefore = window.Node.prototype.insertBefore;
    const isModuleScript = node => node
        && node.nodeType === 1
        && node.tagName === 'SCRIPT'
        && String(node.getAttribute('type') || '').toLowerCase() === 'module';
    const releaseModuleScript = node => {
        const src = node.getAttribute('src') || '(inline)';
        log(`[host] module script not executed by jsdom, releasing: ${src}`);
        window.setTimeout(() => {
            node.dispatchEvent(new window.Event('load'));
        }, 0);
    };
    window.Node.prototype.appendChild = function (node) {
        const result = appendChild.call(this, node);
        if (isModuleScript(node)) releaseModuleScript(node);
        return result;
    };
    window.Node.prototype.insertBefore = function (node, reference) {
        const result = insertBefore.call(this, node, reference);
        if (isModuleScript(node)) releaseModuleScript(node);
        return result;
    };

    if (window.navigator && !window.navigator.clipboard) {
        Object.defineProperty(window.navigator, 'clipboard', {
            configurable: true,
            value: {
                writeText: async () => {},
                readText: async () => '',
                read: async () => [],
                write: async () => {},
            },
        });
    }

    const elementProto = window.Element?.prototype;
    if (elementProto) {
        elementProto.scrollIntoView ??= function () {};
        elementProto.scrollTo ??= function () {};
        elementProto.animate ??= function () {
            return {
                finished: Promise.resolve(),
                cancel: () => {},
                finish: () => {},
                play: () => {},
                pause: () => {},
                onfinish: null,
            };
        };
        elementProto.getBoundingClientRect ??= function () {
            return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, toJSON: () => ({}) };
        };
    }

    // jsdom without the optional `canvas` package logs and returns null. The
    // UI only paints into canvases; give it a harmless 2d context so calls
    // like measureText() do not explode during initialisation.
    const canvasProto = window.HTMLCanvasElement?.prototype;
    if (canvasProto && !canvasProto.__headlessPatched) {
        canvasProto.__headlessPatched = true;
        canvasProto.getContext = function () {
            return {
                canvas: this,
                measureText: text => ({ width: String(text ?? '').length * 6, actualBoundingBoxAscent: 8, actualBoundingBoxDescent: 2 }),
                fillText: () => {},
                strokeText: () => {},
                clearRect: () => {},
                fillRect: () => {},
                drawImage: () => {},
                getImageData: () => ({ data: new Uint8ClampedArray(4) }),
                putImageData: () => {},
                createLinearGradient: () => ({ addColorStop: () => {} }),
                save: () => {},
                restore: () => {},
                scale: () => {},
                translate: () => {},
                setTransform: () => {},
                beginPath: () => {},
                closePath: () => {},
                moveTo: () => {},
                lineTo: () => {},
                arc: () => {},
                fill: () => {},
                stroke: () => {},
                font: '',
                textAlign: 'left',
                textBaseline: 'alphabetic',
            };
        };
    }
}

/**
 * Installs jsdom's window as the process-wide browser global, mirroring the
 * browser's `window === globalThis` assumption as far as Node allows.
 */
const DOM_CONSTRUCTOR_PATTERN = /^(?:HTML|SVG|MathML|DOM|CSS|Document|Window|Node|Element|Event|Mouse|Keyboard|Input|Focus|UI|Pointer|Touch|Wheel|Drag|Submit|Clipboard|FormData|File|Blob|Image|Audio|Option|Text|Comment|Range|Selection|XPath|Mutation|Resize|Intersection)/;

/**
 * Copies every DOM interface constructor jsdom knows about onto globalThis.
 * The client uses DOMPurify hooks and many `instanceof` checks, so an
 * incomplete list of HTML*Element classes breaks rendering.
 */
function installDomConstructors(window, log) {
    for (const key of Object.getOwnPropertyNames(window)) {
        if (!DOM_CONSTRUCTOR_PATTERN.test(key)) continue;
        if (key in globalThis) continue;
        try {
            const value = window[key];
            if (typeof value !== 'function') continue;
            Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
        } catch (error) {
            log(`[host] could not install DOM constructor ${key}: ${error?.message || error}`);
        }
    }
}

/**
 * Interface names the client references as bare globals. Anything jsdom does
 * not expose is stubbed so `instanceof` checks and static constants do not
 * throw; stubs that cannot match real nodes simply make a rendering branch
 * false, which cannot affect the assembled prompt.
 */
const REQUIRED_DOM_GLOBALS = [
    'HTMLUnknownElement', 'HTMLLinkElement', 'HTMLStyleElement', 'HTMLScriptElement',
    'HTMLBodyElement', 'HTMLHeadElement', 'HTMLHtmlElement', 'HTMLDocument', 'HTMLTemplateElement',
    'Document', 'DocumentFragment', 'NodeFilter', 'TreeWalker', 'NodeIterator',
    'CSSRule', 'CSSStyleRule', 'CSSMediaRule', 'CSSSupportsRule', 'CSSKeyframesRule',
    'CSSImportRule', 'CSSFontFaceRule', 'CSSPageRule', 'CSSNamespaceRule',
    'CSSConditionRule', 'CSSGroupingRule', 'XPathResult', 'XPathExpression',
];

function ensureDomGlobals(window, log) {
    for (const name of REQUIRED_DOM_GLOBALS) {
        if (typeof globalThis[name] === 'function') continue;
        let value = typeof window[name] === 'function' ? window[name] : null;
        if (!value) {
            const base = name.startsWith('CSS') ? class {} : (window.HTMLElement ?? class {});
            value = class extends base {};
            Object.defineProperty(value, 'name', { value: name, configurable: true });
            log(`[host] stub DOM global ${name} (jsdom does not expose it)`);
        }
        try {
            Object.defineProperty(globalThis, name, { value, writable: true, configurable: true });
        } catch (error) {
            log(`[host] could not install ${name}: ${error?.message || error}`);
        }
    }
    log(`[host] DOM globals: HTMLUnknownElement window=${typeof window.HTMLUnknownElement} ` +
        `global=${typeof globalThis.HTMLUnknownElement}, HTMLLinkElement window=${typeof window.HTMLLinkElement} ` +
        `global=${typeof globalThis.HTMLLinkElement}, CSSSupportsRule global=${typeof globalThis.CSSSupportsRule}`);
}

function installGlobals(window, log) {
    globalThis.window = window;
    for (const key of FORCED_GLOBALS) {
        try {
            const value = window[key];
            if (value !== undefined) {
                Object.defineProperty(globalThis, key, {
                    value,
                    writable: true,
                    configurable: true,
                });
            }
        } catch (error) {
            log(`[host] could not install global ${key}: ${error?.message || error}`);
        }
    }
    mirrorWindow(window);
    installDomConstructors(window, log);
    ensureDomGlobals(window, log);
}

/**
 * Copies properties that exist on only one side of the window/globalThis split.
 * The app occasionally assigns `window.x` and reads bare `x`, and script.js
 * assigns `globalThis.SillyTavern`, so both directions are needed.
 */
function mirrorWindow(window) {
    for (const source of [window, globalThis]) {
        const target = source === window ? globalThis : window;
        let keys;
        try {
            keys = Object.getOwnPropertyNames(source);
        } catch {
            continue;
        }
        for (const key of keys) {
            if (key in target) continue;
            if (key === 'undefined' || key === 'NaN' || key === 'Infinity' || key === 'globalThis') continue;
            try {
                const value = source[key];
                if (typeof value === 'function' && /^(?:webkit|moz)/.test(key)) continue;
                Object.defineProperty(target, key, {
                    value,
                    writable: true,
                    configurable: true,
                    enumerable: true,
                });
            } catch {
                // getters that throw (cross-origin frames and friends) are irrelevant
            }
        }
    }
}

function readCookie(jar, url) {
    try {
        if (typeof jar.getCookieStringSync === 'function') return jar.getCookieStringSync(url);
        if (typeof jar.getCookieString === 'function') {
            // tough-cookie v2/v3 callback form: not usable synchronously; the
            // jsdom versions used here all expose the *Sync variant.
            return '';
        }
    } catch {
        return '';
    }
    return '';
}

function writeCookies(jar, url, setCookieHeaders) {
    for (const header of setCookieHeaders) {
        try {
            if (typeof jar.setCookieSync === 'function') jar.setCookieSync(header, url);
            else if (typeof jar.setCookie === 'function') jar.setCookie(header, url);
        } catch {
            // ignore malformed cookie
        }
    }
}

/**
 * Routes the page's fetch() through Node's fetch, sharing jsdom's cookie jar so
 * the CSRF token cookie is present on every later request. `intercept` may
 * return a Response to short-circuit a request (used for /generate and the
 * provider status probe).
 */
function installFetch(window, baseUrl, jar, intercept, log) {
    const nodeFetch = globalThis.fetch;
    const wrapper = async (input, init = {}) => {
        const rawUrl = typeof input === 'string' ? input : input?.url;
        const url = new URL(rawUrl, baseUrl).href;
        const options = { ...init };

        if (intercept) {
            const shortCircuit = await intercept(url, options);
            if (shortCircuit) return shortCircuit;
        }

        const headers = new Headers(options.headers || (typeof input === 'object' ? input.headers : undefined));
        const cookie = readCookie(jar, url);
        if (cookie && !headers.has('cookie')) headers.set('cookie', cookie);
        options.headers = headers;

        const method = options.method ?? 'GET';
        let response;
        try {
            response = await nodeFetch(url, options);
        } catch (error) {
            // Dedicated servers close idle keep-alive sockets; undici can pick
            // one up in the race and report "other side closed". The body is
            // an already-serialised string, so one retry is safe.
            const code = error?.cause?.code ?? error?.code;
            if (code !== 'UND_ERR_SOCKET' && code !== 'ECONNRESET') throw error;
            log(`[host] retrying ${url} after ${code}`);
            response = await nodeFetch(url, options);
        }
        log(`[host] fetch ${method} ${url} -> ${response.status}`);
        let setCookies = [];
        try {
            setCookies = typeof response.headers.getSetCookie === 'function'
                ? response.headers.getSetCookie()
                : [response.headers.get('set-cookie')].filter(Boolean);
        } catch {
            setCookies = [];
        }
        if (setCookies.length > 0) writeCookies(jar, url, setCookies);
        return response;
    };

    window.fetch = wrapper;
    globalThis.fetch = wrapper;
    log('[host] fetch installed (Node fetch + jsdom cookie jar)');
}


/**
 * Lists `<script type="module" src="...">` entries in document order. These
 * are separate browser entry points, not a single graph rooted at script.js.
 */
function extractModuleEntryPoints(html) {
    const entries = [];
    const pattern = /<script\b[^>]*>/gi;
    for (const match of html.matchAll(pattern)) {
        const tag = match[0];
        if (!/type\s*=\s*["']module["']/i.test(tag)) continue;
        const src = /src\s*=\s*["']([^"']+)["']/i.exec(tag);
        if (!src) continue;
        entries.push(src[1].split('?')[0]);
    }
    if (!entries.includes('script.js') && !entries.includes('/script.js')) {
        entries.push('script.js');
    }
    return entries;
}

function waitForCondition(predicate, { timeoutMs, label, logs }) {
    return new Promise((resolve, reject) => {
        const started = Date.now();
        const tick = () => {
            let value;
            try {
                value = predicate();
            } catch (error) {
                reject(new Error(`${label} threw: ${error?.message || error}\n${tailLogs(logs)}`));
                return;
            }
            if (value) {
                resolve(value);
                return;
            }
            if (Date.now() - started > timeoutMs) {
                reject(new Error(`Timed out after ${timeoutMs}ms waiting for ${label}\n${tailLogs(logs)}`));
                return;
            }
            setTimeout(tick, 100);
        };
        tick();
    });
}

/**
 * Boots the client.
 *
 * @param {object} options
 * @param {string} options.baseUrl  Origin the SillyTavern server is listening on.
 * @param {string} options.publicDir Absolute path to SillyTavern/public.
 * @param {(url: string, init: object) => (Response|Promise<Response>|null)} [options.intercept]
 * @param {(line: string) => void} [options.onLog]
 * @param {number} [options.readyTimeoutMs]
 * @returns {Promise<{window: Window, dom: JSDOM, app: object, logs: string[], waitFor: Function, close: Function}>}
 */
export async function createDomHost({
    baseUrl,
    publicDir,
    intercept = null,
    onLog = () => {},
    readyTimeoutMs = 300_000,
}) {
    const logs = [];
    let lastLine = null;
    let repeated = 0;
    const push = text => {
        logs.push(text);
        if (logs.length > LOG_LIMIT) logs.shift();
        onLog(text);
    };
    // Initialisation loops can emit the same warning thousands of times; keep
    // the history readable without losing it.
    const log = line => {
        const text = String(line);
        if (text === lastLine) {
            repeated++;
            return;
        }
        if (repeated > 0) {
            push(`[repeated ${repeated} more times] ${lastLine}`);
            repeated = 0;
        }
        lastLine = text;
        push(text);
    };

    const indexPath = path.join(publicDir, 'index.html');
    if (!fs.existsSync(indexPath)) {
        throw new Error(`index.html not found at ${indexPath}`);
    }
    const html = fs.readFileSync(indexPath, 'utf8');

    // jsdom executes the classic scripts in index.html (jQuery, toastr,
    // polyfills) and fetches stylesheets from the real server.
    const dom = new JSDOM(html, {
        url: baseUrl,
        runScripts: 'dangerously',
        resources: 'usable',
        pretendToBeVisual: true,
        virtualConsole: createVirtualConsole(log),
    });
    const window = dom.window;

    await new Promise((resolve, reject) => {
        if (window.document.readyState === 'complete') return resolve();
        const timer = setTimeout(() => reject(new Error('index.html never finished loading')), 60_000);
        window.addEventListener('load', () => {
            clearTimeout(timer);
            resolve();
        });
    });
    log('[host] index.html loaded (classic scripts evaluated by jsdom)');

    installGlobals(window, log);
    installShims(window, log);
    // Pick up the shims (matchMedia, observers, CSSOM rules) on the Node side too.
    mirrorWindow(window);
    installDomConstructors(window, log);
    ensureDomGlobals(window, log);
    installFetch(window, baseUrl, dom.cookieJar, intercept, log);

    // The client uses browser-root-absolute imports (/script.js, /lib.js); map
    // them onto public/ exactly as the server would serve them.
    // Shims for UMD packages need to require() from the SillyTavern install.
    process.env.ST_REQUIRE_BASE = path.resolve(publicDir, '..', 'package.json');
    register('./browser-resolve-hook.mjs', import.meta.url, {
        data: { publicDirUrl: pathToFileURL(publicDir + path.sep).href },
    });

    // Node owns module evaluation; jsdom cannot run <script type="module">.
    // The entries are imported in document order, exactly as a browser would
    // evaluate them: several upstream modules deliberately rely on an earlier
    // entry (for example scripts/i18n.js) breaking a circular import first.
    const entryPointSrcs = extractModuleEntryPoints(html);
    log(`[host] module entry points: ${entryPointSrcs.join(', ')}`);
    let app;
    try {
        for (const src of entryPointSrcs) {
            const entryPath = path.join(publicDir, src.replace(/^\/+/, ''));
            const entryUrl = pathToFileURL(entryPath).href;
            log(`[host] importing ${entryUrl}`);
            const namespace = await import(entryUrl);
            if (src === 'script.js' || src === '/script.js') app = namespace;
        }
    } catch (error) {
        throw new Error(
            `Failed to evaluate the SillyTavern module graph in jsdom: ${error?.stack || error}\n` +
            `--- last page log lines ---\n${tailLogs(logs)}`,
        );
    }
    log('[host] module graph evaluated');

    // script.js sets `globalThis.SillyTavern`; expose it on window as in a tab.
    if (globalThis.SillyTavern && !window.SillyTavern) {
        window.SillyTavern = globalThis.SillyTavern;
    }
    if (window.SillyTavern && !globalThis.SillyTavern) {
        globalThis.SillyTavern = window.SillyTavern;
    }

    const mirrorTimer = setInterval(() => mirrorWindow(window), 500);
    mirrorTimer.unref?.();

    const waitFor = (predicate, options = {}) => waitForCondition(predicate, {
        timeoutMs: options.timeoutMs ?? readyTimeoutMs,
        label: options.label ?? 'condition',
        logs,
    });

    // Progress heartbeat: when initialisation stalls, show what the client
    // has and has not reached yet.
    const heartbeat = setInterval(() => {
        try {
            const st = window.SillyTavern ?? globalThis.SillyTavern;
            const context = st?.getContext?.();
            log(`[wait] ctx=${Boolean(context)} settingsReady=${app?.settingsReady} ` +
                `chars=${context?.characters?.length ?? '-'} ` +
                `source=${context?.chatCompletionSettings?.chat_completion_source ?? '-'} ` +
                `online=${context?.onlineStatus ?? '-'}`);
        } catch (error) {
            log(`[wait] heartbeat failed: ${error?.message || error}`);
        }
    }, 15_000);
    heartbeat.unref?.();

    // Readiness must not depend on the selected provider. This gate used to
    // require `chat_completion_source === 'openai'`, which was only true while
    // settings.json still held the default; a user who saves the built-in
    // "OpenAI 兼容" (custom) or OpenRouter preset would never become ready and
    // every chat turn would fail after the 300 s timeout. Any non-empty source is
    // a valid ready state now.
    try {
        await waitFor(() => {
            const st = window.SillyTavern ?? globalThis.SillyTavern;
            if (!st?.getContext) return false;
            const ctx = st.getContext();
            const source = ctx.chatCompletionSettings?.chat_completion_source;
            return Array.isArray(ctx.characters)
                && ctx.characters.length > 0
                && typeof source === 'string'
                && source.length > 0;
        }, { label: 'window.SillyTavern context (characters + openai settings)' });
    } finally {
        clearInterval(heartbeat);
    }
    log('[host] application ready');

    return {
        window,
        dom,
        app,
        logs,
        waitFor,
        close: () => {
            clearInterval(mirrorTimer);
            try {
                window.close();
            } catch {
                // nothing to clean up
            }
        },
    };
}
