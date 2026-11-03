/**
 * Node module resolution hook for code written for a browser.
 *
 * Two browser-isms need translating:
 *   1. Root-absolute specifiers (`/script.js`, `/lib.js`, `/scripts/utils.js`)
 *      that the browser resolves against the page origin. Under Node they
 *      would resolve against the filesystem root, so they are mapped onto
 *      SillyTavern/public — the same files the server would serve.
 *   2. A couple of UMD-only packages whose named exports Node's CJS detection
 *      cannot see. They are redirected to small require()-based shims.
 *
 * Registered by dom-host.mjs before the client module graph is imported.
 */
const BARE_SHIMS = new Map([
    ['slidetoggle', './shims/slidetoggle.mjs'],
    ['js-sha256', './shims/js-sha256.mjs'],
]);

let publicDirUrl = null;

export function initialize(data) {
    publicDirUrl = data?.publicDirUrl ?? null;
}

export async function resolve(specifier, context, nextResolve) {
    const shim = BARE_SHIMS.get(specifier);
    if (shim) {
        const url = new URL(shim, import.meta.url).href;
        return { url, shortCircuit: true };
    }

    if (publicDirUrl && specifier.startsWith('/') && !specifier.startsWith('//')) {
        const base = publicDirUrl.endsWith('/') ? publicDirUrl : `${publicDirUrl}/`;
        const mapped = new URL(`.${specifier}`, base).href;
        return nextResolve(mapped, context);
    }

    return nextResolve(specifier, context);
}
