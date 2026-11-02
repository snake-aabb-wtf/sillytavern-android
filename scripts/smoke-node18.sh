#!/usr/bin/env bash
# Boot a staged runtime with Node 18 and assert the UI/API answer.
#
# Usage:
#   NODE18=/path/to/node18 bash scripts/smoke-node18.sh dist/runtime
#
# The staged directory is what stage-runtime.sh produces (server/ + seed/).
# The seed is copied to a throwaway data root first, mirroring the app's first
# launch, so the artifact stays pristine.
set -euo pipefail

RUNTIME_DIR="${1:-}"
if [[ -z "$RUNTIME_DIR" || ! -d "$RUNTIME_DIR" ]]; then
    printf 'usage: %s <staged-runtime-dir>  (expected server/ and seed/ inside)\n' "$0" >&2
    exit 2
fi
RUNTIME_DIR="$(cd "$RUNTIME_DIR" && pwd)"
SERVER_DIR="$RUNTIME_DIR/server"
SEED_DIR="$RUNTIME_DIR/seed"
NODE_BIN="${NODE18:-node}"
TIMEOUT="${SMOKE_TIMEOUT:-180}"
PORT_OVERRIDE="${PORT:-}"

TMP_DIR=""
SERVER_PID=""

die() {
    printf 'smoke-node18: %s\n' "$*" >&2
    exit 1
}

resolve_node() {
    if [[ "$NODE_BIN" == */* ]]; then
        [[ -x "$NODE_BIN" ]] || die "NODE18 is not executable: $NODE_BIN"
    else
        NODE_BIN="$(command -v "$NODE_BIN" || true)"
        [[ -n "$NODE_BIN" ]] || die "NODE18 not found in PATH"
    fi
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

http_code() {
    curl -sS -o /dev/null -m 5 -w '%{http_code}' "$@" 2>/dev/null || true
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
    if [[ -n "${TMP_DIR:-}" ]]; then
        rm -rf -- "$TMP_DIR"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

resolve_node
NODE_MAJOR="$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')"
[[ "$NODE_MAJOR" =~ ^[0-9]+$ ]] || die "cannot determine node major version"
(( NODE_MAJOR >= 18 )) || die "Node >= 18 required, found $("$NODE_BIN" -v)"
if (( NODE_MAJOR != 18 )); then
    printf 'smoke-node18: warning: expected Node 18, running %s (%s)\n' "$NODE_MAJOR" "$("$NODE_BIN" -v)" >&2
fi

[[ -f "$SERVER_DIR/server.js" ]] || die "missing $SERVER_DIR/server.js"
[[ -f "$SERVER_DIR/public/lib.bundle.js" ]] || die "missing prebuilt $SERVER_DIR/public/lib.bundle.js"
[[ -d "$SEED_DIR" ]] || die "missing $SEED_DIR (seeded data root)"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/st-smoke.XXXXXX")"
DATA_DIR="$TMP_DIR/data"
LOG="$TMP_DIR/server.log"
COOKIE_JAR="$TMP_DIR/cookies.txt"
mkdir -p "$DATA_DIR"
cp -a "$SEED_DIR/." "$DATA_DIR/"

if [[ -n "$PORT_OVERRIDE" ]]; then
    PORT="$PORT_OVERRIDE"
else
    PORT="$(free_port)"
fi
[[ "$PORT" =~ ^[0-9]+$ ]] || die "failed to pick a free port"

printf '==> Booting %s with %s on 127.0.0.1:%s\n' "$SERVER_DIR" "$("$NODE_BIN" -v)" "$PORT"
(
    cd "$SERVER_DIR"
    exec env ST_SKIP_WEBPACK=1 ST_MOBILE=1 \
        "$NODE_BIN" server.js --port "$PORT" --dataRoot "$DATA_DIR"
) >"$LOG" 2>&1 &
SERVER_PID=$!

READY=0
DEADLINE=$((SECONDS + TIMEOUT))
while (( SECONDS < DEADLINE )); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        printf 'smoke-node18: server exited during startup\n' >&2
        tail -n 100 "$LOG" >&2 || true
        exit 1
    fi
    CODE="$(http_code "http://127.0.0.1:$PORT/")"
    if [[ "$CODE" == "200" ]]; then
        READY=1
        break
    fi
    sleep 1
done

if (( ! READY )); then
    printf 'smoke-node18: root page was not 200 within %ss\n' "$TIMEOUT" >&2
    tail -n 100 "$LOG" >&2 || true
    exit 1
fi

LIB_CODE="$(http_code "http://127.0.0.1:$PORT/lib.js")"
if [[ "$LIB_CODE" != "200" ]]; then
    printf 'smoke-node18: GET /lib.js returned %s (expected 200)\n' "$LIB_CODE" >&2
    tail -n 100 "$LOG" >&2 || true
    exit 1
fi

# /api/settings/get is POST + CSRF protected. Get a token so the assertion
# exercises the real handler; if the token dance fails, an unauthenticated
# POST must still not be a 5xx (403 is acceptable per the smoke contract).
TOKEN="$(curl -sS -m 5 -c "$COOKIE_JAR" "http://127.0.0.1:$PORT/csrf-token" 2>/dev/null \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' || true)"
if [[ -n "$TOKEN" ]]; then
    SETTINGS_CODE="$(http_code -b "$COOKIE_JAR" -H "x-csrf-token: $TOKEN" \
        -X POST "http://127.0.0.1:$PORT/api/settings/get")"
else
    SETTINGS_CODE="$(http_code -X POST "http://127.0.0.1:$PORT/api/settings/get")"
fi

if [[ ! "$SETTINGS_CODE" =~ ^[0-9]{3}$ ]] || (( 10#$SETTINGS_CODE >= 500 )); then
    printf 'smoke-node18: POST /api/settings/get returned %s (expected non-5xx)\n' "${SETTINGS_CODE:-no response}" >&2
    tail -n 100 "$LOG" >&2 || true
    exit 1
fi

printf '    GET /                    -> %s\n' "$CODE"
printf '    GET /lib.js              -> %s\n' "$LIB_CODE"
printf '    POST /api/settings/get   -> %s\n' "$SETTINGS_CODE"
printf '==> Smoke OK\n'
