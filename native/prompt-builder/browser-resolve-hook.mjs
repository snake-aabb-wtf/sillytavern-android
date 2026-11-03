/**
 * Node module resolution hook for code written for a browser.
 *
 * The SillyTavern client imports some modules with root-absolute specifiers
 * (`/script.js`, `/lib.js`, `/scripts/utils.js`), which the browser resolves
 * against the page origin. Under Node those would resolve against the
 * filesystem root, so this hook maps them onto SillyTavern/public — the same
 * files the server would serve.
 *
 * Registered by dom-host.mjs before the client module graph is imported.
 */
let publicDirUrl = null;

export function initialize(data) {
    publicDirUrl = data?.publicDirUrl ?? null;
}

export async function resolve(specifier, context, nextResolve) {
    if (publicDirUrl && specifier.startsWith('/') && !specifier.startsWith('//')) {
        const base = publicDirUrl.endsWith('/') ? publicDirUrl : `${publicDirUrl}/`;
        const mapped = new URL(`.${specifier}`, base).href;
        return nextResolve(mapped, context);
    }
    return nextResolve(specifier, context);
}
