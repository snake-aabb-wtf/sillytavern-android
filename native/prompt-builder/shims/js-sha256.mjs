/**
 * `js-sha256` exports a function object built at runtime, so Node's CJS
 * named-export detection cannot see `sha256`. Load it with require() and
 * re-export the name public/lib.js asks for.
 */
import { createRequire } from 'node:module';

const require = createRequire(process.env.ST_REQUIRE_BASE ?? import.meta.url);
const mod = require('js-sha256');
const sha256 = mod?.sha256 ?? mod?.default?.sha256 ?? mod;

export { sha256 };
export default sha256;
