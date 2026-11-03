#!/usr/bin/env bash
# Stage the SillyTavern runtime consumed by the Android shell.
#
# Produces:
#   dist/runtime/server  server sources, production node_modules, public/lib.bundle.js
#   dist/runtime/seed    data root captured from one real boot (default content seeded)
#   dist/runtime.zip     both directories above, ready for android/app/src/main/assets
#
# Webpack is memory hungry, so the full run belongs in CI. This script only
# documents/executes the pipeline; see docs/superpowers/specs for the memory note.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ST_DIR="$ROOT/SillyTavern"
DIST_DIR="$ROOT/dist"
STAGE_DIR="$DIST_DIR/runtime"
SERVER_DIR="$STAGE_DIR/server"
SEED_DIR="$STAGE_DIR/seed"
ZIP_PATH="$DIST_DIR/runtime.zip"
BOOT_LOG="$DIST_DIR/stage-boot.log"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-900}"
NODE_BIN="${NODE_BIN:-node}"

TMP_DATA=""
SERVER_PID=""
STAGE_OK=0

die() {
    printf 'stage-runtime: %s\n' "$*" >&2
    exit 1
}

resolve_node() {
    local candidate="$NODE_BIN"
    if [[ "$candidate" != */* ]]; then
        candidate="$(command -v "$candidate" || true)"
        [[ -n "$candidate" ]] || die "node not found in PATH"
    fi
    [[ -x "$candidate" ]] || die "node binary is not executable: $candidate"
    # Absolutize: servers/commands are started after `cd` into another directory.
    NODE_BIN="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
}

free_port() {
    "$NODE_BIN" -e '
        const net = require("node:net");
        const srv = net.createServer();
        srv.unref();
        srv.listen(0, "127.0.0.1", () => {
            console.log(srv.address().port);
            srv.close();
        });
    '
}

stop_server() {
    if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        for _ in $(seq 1 50); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 0.1
        done
        kill -KILL "$SERVER_PID" 2>/dev/null || true
    fi
    if [[ -n "${SERVER_PID:-}" ]]; then
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""
}

cleanup() {
    stop_server
    if [[ -n "${TMP_DATA:-}" ]]; then
        rm -rf -- "$TMP_DATA"
    fi
    if (( STAGE_OK )); then
        rm -f -- "$BOOT_LOG"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

resolve_node
NODE_MAJOR="$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')"
[[ "$NODE_MAJOR" =~ ^[0-9]+$ ]] || die "cannot determine node major version"
(( NODE_MAJOR >= 20 )) || die "Node >= 20 required for the webpack build, found $("$NODE_BIN" -v)"
printf 'Using Node %s (%s)\n' "$("$NODE_BIN" -v)" "$NODE_BIN"

for tool in npm tar zip unzip curl; do
    command -v "$tool" >/dev/null 2>&1 || die "required tool not found: $tool"
done
[[ -f "$ST_DIR/server.js" ]] || die "SillyTavern sources not found at $ST_DIR"
[[ -f "$ST_DIR/package-lock.json" ]] || die "package-lock.json not found at $ST_DIR"

printf '==> Resetting %s\n' "$STAGE_DIR"
rm -rf -- "$STAGE_DIR" "$ZIP_PATH"
mkdir -p "$SERVER_DIR" "$SEED_DIR"

printf '==> Copying server sources (no node_modules/data/dist/tests)\n'
tar -C "$ST_DIR" \
    --exclude='./node_modules' \
    --exclude='./data' \
    --exclude='./dist' \
    --exclude='./.git' \
    --exclude='./tests' \
    --exclude='./.github' \
    --exclude='./backups' \
    --exclude='./colab' \
    --exclude='./docker' \
    -cf - . | tar -C "$SERVER_DIR" -xf -

printf '==> Installing dependencies (dev included: webpack needs them)\n'
(cd "$SERVER_DIR" && npm ci --include=dev --no-audit --no-fund)

# The native client's API route lives in native/ and is mounted by appending to
# the staged server-main.js copy (SillyTavern/ itself is never modified). The
# route's prompt builder is a runtime dependency here, so install only its
# production tree (jsdom); the parity tests' Playwright stays out of the APK.
printf '==> Overlaying native API endpoints\n'
mkdir -p "$SERVER_DIR/native"
tar -C "$ROOT/native" \
    --exclude='./node_modules' \
    --exclude='./test' \
    -cf - . | tar -C "$SERVER_DIR/native" -xf -
(cd "$SERVER_DIR/native" && npm ci --omit=dev --no-audit --no-fund)
[[ -f "$SERVER_DIR/native/endpoints/native-chat.mjs" ]] || die "native-chat.mjs was not overlaid"
[[ -d "$SERVER_DIR/native/node_modules/jsdom" ]] || die "jsdom was not installed for the native builder"

STAGED_MAIN="$SERVER_DIR/src/server-main.js"
[[ -f "$STAGED_MAIN" ]] || die "staged server-main.js not found at $STAGED_MAIN"
grep -q "'/api/native'" "$STAGED_MAIN" && die "server-main.js already mounts /api/native"
# The placeholder is registered synchronously while this module evaluates, so
# it lands ahead of the terminal 404 middleware that the async init chain
# registers later. The dynamic import then fills in the real router.
cat >> "$STAGED_MAIN" <<'EOF'

// ST-ANDROID: native client API. Registered before the async startup chain can
// install its terminal 404 handler; the router itself loads asynchronously.
let nativeRouter = null;
app.use('/api/native', (req, res, next) => (nativeRouter ? nativeRouter(req, res, next) : next()));
const { default: loadedNativeRouter } = await import('../native/endpoints/native-chat.mjs');
nativeRouter = loadedNativeRouter;
console.info('[native] /api/native mounted'); // ST-ANDROID
EOF
printf '==> Mounted /api/native in %s\n' "$STAGED_MAIN"

TMP_DATA="$(mktemp -d "${TMPDIR:-/tmp}/st-stage-data.XXXXXX")"
PORT="$(free_port)"
[[ "$PORT" =~ ^[0-9]+$ ]] || die "failed to pick a free port"

printf '==> Booting once on 127.0.0.1:%s to compile lib.js and seed default content\n' "$PORT"
printf '    data root: %s\n' "$TMP_DATA"
printf '    boot log:  %s\n' "$BOOT_LOG"
(
    cd "$SERVER_DIR"
    exec env ST_SKIP_WEBPACK=0 NODE_ENV=production NATIVE_CHAT_WARMUP=0 \
        "$NODE_BIN" server.js --port "$PORT" --dataRoot "$TMP_DATA"
) >"$BOOT_LOG" 2>&1 &
SERVER_PID=$!

READY=0
DEADLINE=$((SECONDS + BOOT_TIMEOUT))
while (( SECONDS < DEADLINE )); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        printf 'stage-runtime: server exited during boot\n' >&2
        tail -n 100 "$BOOT_LOG" >&2 || true
        exit 1
    fi
    CODE="$(curl -sS -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)"
    if [[ "$CODE" == "200" ]]; then
        READY=1
        break
    fi
    sleep 2
done

if (( ! READY )); then
    printf 'stage-runtime: server was not ready within %ss\n' "$BOOT_TIMEOUT" >&2
    tail -n 100 "$BOOT_LOG" >&2 || true
    exit 1
fi

printf '==> Server is up; stopping it cleanly\n'
stop_server

LIB_JS="$(find "$TMP_DATA/_webpack" -type f -path '*/output/lib.js' -print -quit 2>/dev/null || true)"
[[ -n "$LIB_JS" && -s "$LIB_JS" ]] || die "webpack did not produce <dataRoot>/_webpack/*/output/lib.js"
printf '==> Copying compiled bundle: %s -> public/lib.bundle.js\n' "$LIB_JS"
cp -- "$LIB_JS" "$SERVER_DIR/public/lib.bundle.js"
[[ -s "$SERVER_DIR/public/lib.bundle.js" ]] || die "public/lib.bundle.js is empty"

# public/ cannot be dropped: the native client's headless prompt builder loads
# the real web client from it (index.html, script.js and the whole scripts/
# module graph, plus locales/ and lib/). Only the assets a browser would paint
# are safe to remove. The parity workflow prunes a copy with the same script,
# so a needed asset hidden in one of these directories fails CI.
printf '==> Pruning browser-only assets from public/ (fonts, images, CSS, sounds, icons)\n'
"$ROOT/scripts/prune-public.sh" --require-bundle "$SERVER_DIR"

[[ -d "$TMP_DATA/default-user" ]] || die "default content was not seeded into <dataRoot>/default-user"
[[ -s "$TMP_DATA/default-user/settings.json" ]] || die "seeded default-user/settings.json is missing"

printf '==> Pruning dev dependencies\n'
(cd "$SERVER_DIR" && npm prune --omit=dev --no-audit --no-fund)
if [[ -d "$SERVER_DIR/node_modules/typescript" ]]; then
    die "dev dependencies are still present after npm prune (typescript found)"
fi

# getCookieSecret() writes cookie-secret.txt on first boot. Shipping it would
# give every APK the same CSRF/session-signing key, extractable from the asset.
# heartbeat.json is runtime state, not seed data. content.log is deliberately
# kept (root + per-user): it records seeded revisions and prevents re-seeding.
printf '==> Removing per-boot secrets and runtime state from seed\n'
rm -f -- "$TMP_DATA/cookie-secret.txt" "$TMP_DATA/heartbeat.json"

printf '==> Copying seeded data root to seed/ (webpack cache excluded: it is build-only)\n'
tar -C "$TMP_DATA" --exclude='./_webpack' -cf - . | tar -C "$SEED_DIR" -xf -
[[ -s "$SEED_DIR/default-user/settings.json" ]] || die "seed/default-user/settings.json is missing"
[[ ! -e "$SEED_DIR/cookie-secret.txt" ]] || die "seed/cookie-secret.txt must not ship"
[[ ! -e "$SEED_DIR/heartbeat.json" ]] || die "seed/heartbeat.json must not ship"
[[ -f "$SEED_DIR/default-user/content.log" ]] || die "seed/default-user/content.log is missing"

printf '==> Packaging %s (root entries: server/ seed/)\n' "$ZIP_PATH"
(cd "$STAGE_DIR" && zip -q -r -X "$ZIP_PATH" server seed)
[[ -s "$ZIP_PATH" ]] || die "runtime.zip was not created"
unzip -l "$ZIP_PATH" | grep -Eq '[[:space:]]server/$' || die "runtime.zip has no root server/ entry"
unzip -l "$ZIP_PATH" | grep -Eq '[[:space:]]seed/$' || die "runtime.zip has no root seed/ entry"

printf '\n==> Done\n'
du -sh "$SERVER_DIR" "$SEED_DIR" "$ZIP_PATH"
STAGE_OK=1
