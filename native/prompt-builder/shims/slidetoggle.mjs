/**
 * `slidetoggle` ships a UMD bundle whose exports are produced by a factory
 * call (`module.exports = t()`), so Node's CJS named-export detection cannot
 * see `toggle`/`show`/`hide`. Load it with require() and re-export the names
 * public/lib.js asks for.
 */
import { createRequire } from 'node:module';

const require = createRequire(process.env.ST_REQUIRE_BASE ?? import.meta.url);
const mod = require('slidetoggle');
const api = mod?.default ?? mod;

export const toggle = api.toggle;
export const show = api.show;
export const hide = api.hide;
export default api;
