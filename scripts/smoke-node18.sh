#!/usr/bin/env bash
# Boot a staged runtime with Node 18 and assert the UI/API answer.
#
# Usage:
#   NODE18=/path/to/node18 bash scripts/smoke-node18.sh dist/runtime
#   NODE18=/path/to/node18 bash scripts/smoke-node18.sh dist/runtime.zip
#
# Accepts either the staged directory (server/ + seed/) or the shipped archive.
# A .zip is extracted to a throwaway directory, so the real shipped artifact is
# what gets exercised, not just the pre-zip build tree.
# The seed is copied to a throwaway data root first, mirroring the app's first
# launch, so the runtime stays pristine.
set -euo pipefail

RUNTIME_ARG="${1:-}"
NODE_BIN="${NODE18:-node}"
TIMEOUT="${SMOKE_TIMEOUT:-180}"
PORT_OVERRIDE="${PORT:-}"

TMP_DIR=""
SERVER_PID=""

die() {
    printf 'smoke-node18: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'usage: %s <staged-runtime-dir|runtime.zip>  (expected server/ and seed/ inside)\n' "$0" >&2
    exit 2
}

resolve_node() {
    local candidate="$NODE_BIN"
    if [[ "$candidate" != */* ]]; then
        candidate="$(command -v "$candidate" || true)"
        [[ -n "$candidate" ]] || die "NODE18 not found in PATH"
    fi
    [[ -x "$candidate" ]] || die "NODE18 is not executable: $candidate"
    # Absolutize: the server is started after `cd "$SERVER_DIR"`.
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

[[ -n "$RUNTIME_ARG" ]] || usage
[[ -e "$RUNTIME_ARG" ]] || die "no such file or directory: $RUNTIME_ARG"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/st-smoke.XXXXXX")"
if [[ -f "$RUNTIME_ARG" ]]; then
    [[ "$RUNTIME_ARG" == *.zip ]] || die "expected a directory or a .zip archive: $RUNTIME_ARG"
    command -v unzip >/dev/null 2>&1 || die "unzip is required to smoke a .zip artifact"
    mkdir -p "$TMP_DIR/runtime"
    unzip -q "$RUNTIME_ARG" -d "$TMP_DIR/runtime" || die "failed to extract $RUNTIME_ARG"
    RUNTIME_DIR="$TMP_DIR/runtime"
else
    RUNTIME_DIR="$(cd "$RUNTIME_ARG" && pwd)"
fi

SERVER_DIR="$RUNTIME_DIR/server"
SEED_DIR="$RUNTIME_DIR/seed"

resolve_node
command -v curl >/dev/null 2>&1 || die "curl is required but was not found in PATH"

NODE_MAJOR="$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')"
[[ "$NODE_MAJOR" =~ ^[0-9]+$ ]] || die "cannot determine node major version"
(( NODE_MAJOR >= 18 )) || die "Node >= 18 required, found $("$NODE_BIN" -v)"
if (( NODE_MAJOR != 18 )); then
    if [[ "${ALLOW_ANY_NODE:-0}" != "1" ]]; then
        die "Node 18 required, found $("$NODE_BIN" -v); set ALLOW_ANY_NODE=1 to override"
    fi
    printf 'smoke-node18: warning: ALLOW_ANY_NODE=1, running %s\n' "$("$NODE_BIN" -v)" >&2
fi

[[ -f "$SERVER_DIR/server.js" ]] || die "missing $SERVER_DIR/server.js"
[[ -f "$SERVER_DIR/public/lib.bundle.js" ]] || die "missing prebuilt $SERVER_DIR/public/lib.bundle.js"
[[ -d "$SEED_DIR" ]] || die "missing $SEED_DIR (seeded data root)"
[[ -s "$SEED_DIR/default-user/settings.json" ]] || die "missing seeded $SEED_DIR/default-user/settings.json"

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

# 000 (connection failure) and empty responses must never pass as "not 5xx".
if [[ ! "$SETTINGS_CODE" =~ ^[0-9]{3}$ ]] \
    || [[ "$SETTINGS_CODE" == "000" ]] \
    || (( 10#$SETTINGS_CODE >= 500 )); then
    printf 'smoke-node18: POST /api/settings/get returned %s (expected non-5xx)\n' "${SETTINGS_CODE:-no response}" >&2
    tail -n 100 "$LOG" >&2 || true
    exit 1
fi

printf '    GET /                    -> %s\n' "$CODE"
printf '    GET /lib.js              -> %s\n' "$LIB_CODE"
printf '    POST /api/settings/get   -> %s\n' "$SETTINGS_CODE"
printf '==> Smoke OK\n'
