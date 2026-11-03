#!/usr/bin/env bash
# Boot a staged runtime and assert the native endpoint is mounted.
#
#   bash scripts/verify-native-endpoint.sh dist/runtime
#
# The staged runtime is the artifact that ships in the APK: server/ has the
# native/ overlay and the appended mount in src/server-main.js. This script
# proves the mount actually answers before the terminal 404 middleware, using
# the real CSRF handshake the native client performs.
#
# It does not exercise the prompt builder: POST with an empty body must be
# rejected with 400 before the builder is touched, so this stays fast and needs
# no API key. Builder warm latency is measured by native/test/latency-native-chat.mjs.
set -euo pipefail

RUNTIME_ARG="${1:-dist/runtime}"
NODE_BIN="${NODE_BIN:-node}"
TIMEOUT="${VERIFY_TIMEOUT:-180}"

TMP_DIR=""
SERVER_PID=""

die() {
    printf 'verify-native-endpoint: %s\n' "$*" >&2
    exit 1
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
# Keep the server log for the CI job when the assertion fails (the temp dir is
# deleted on exit either way).
keep_log_on_failure() {
    local status=$?
    if (( status != 0 )) && [[ -n "${LOG:-}" && -f "$LOG" ]]; then
        cp -f -- "$LOG" "${RUNTIME_ARG%/}/../native-endpoint.log" 2>/dev/null || true
    fi
    cleanup
    exit "$status"
}
trap keep_log_on_failure EXIT
trap 'exit 130' INT TERM

[[ -d "$RUNTIME_ARG" ]] || die "no staged runtime at $RUNTIME_ARG"
RUNTIME_DIR="$(cd "$RUNTIME_ARG" && pwd)"
SERVER_DIR="$RUNTIME_DIR/server"
SEED_DIR="$RUNTIME_DIR/seed"

[[ -f "$SERVER_DIR/server.js" ]] || die "missing $SERVER_DIR/server.js"
[[ -f "$SERVER_DIR/src/server-main.js" ]] || die "missing $SERVER_DIR/src/server-main.js"
[[ -f "$SERVER_DIR/native/endpoints/native-chat.mjs" ]] || die "native/ overlay missing from the staged server"
[[ -d "$SERVER_DIR/native/node_modules/jsdom" ]] || die "jsdom is missing from the staged native/ tree"
grep -q "app.use('/api/native'" "$SERVER_DIR/src/server-main.js" \
    || die "staged server-main.js does not mount /api/native"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/st-native-endpoint.XXXXXX")"
DATA_DIR="$TMP_DIR/data"
LOG="$TMP_DIR/server.log"
COOKIE_JAR="$TMP_DIR/cookies.txt"
BODY_FILE="$TMP_DIR/body.json"
mkdir -p "$DATA_DIR"
cp -a "$SEED_DIR/." "$DATA_DIR/"

PORT="$(free_port)"
[[ "$PORT" =~ ^[0-9]+$ ]] || die "failed to pick a free port"

printf '==> Booting %s on 127.0.0.1:%s\n' "$SERVER_DIR" "$PORT"
(
    cd "$SERVER_DIR"
    # Skip webpack: the staged runtime ships public/lib.bundle.js. Keep the
    # builder cold; this check only needs the route.
    exec env ST_SKIP_WEBPACK=1 ST_MOBILE=1 NATIVE_CHAT_WARMUP=0 \
        "$NODE_BIN" server.js --port "$PORT" --dataRoot "$DATA_DIR"
) >"$LOG" 2>&1 &
SERVER_PID=$!

READY=0
DEADLINE=$((SECONDS + TIMEOUT))
while (( SECONDS < DEADLINE )); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        printf 'verify-native-endpoint: server exited during startup\n' >&2
        tail -n 120 "$LOG" >&2 || true
        exit 1
    fi
    if [[ "$(http_code "http://127.0.0.1:$PORT/")" == "200" ]]; then
        READY=1
        break
    fi
    sleep 1
done
if (( ! READY )); then
    printf 'verify-native-endpoint: server was not ready within %ss\n' "$TIMEOUT" >&2
    tail -n 120 "$LOG" >&2 || true
    exit 1
fi

grep -q '\[native\] /api/native mounted' "$LOG" \
    || { printf 'verify-native-endpoint: mount log line is missing\n' >&2; tail -n 120 "$LOG" >&2; exit 1; }

# The route sits behind CSRF like every other POST. Do the same handshake the
# native client does, then send an empty body.
TOKEN="$(curl -sS -m 5 -c "$COOKIE_JAR" "http://127.0.0.1:$PORT/csrf-token" 2>/dev/null \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' || true)"
[[ -n "$TOKEN" ]] || die "could not obtain a CSRF token"

CODE="$(curl -sS -m 10 -o "$BODY_FILE" -w '%{http_code}' \
    -b "$COOKIE_JAR" -H "x-csrf-token: $TOKEN" -H 'content-type: application/json' \
    -X POST -d '{}' "http://127.0.0.1:$PORT/api/native/chat" 2>/dev/null || true)"

if [[ "$CODE" != "400" ]]; then
    printf 'verify-native-endpoint: POST /api/native/chat returned %s (expected 400)\n' "${CODE:-no response}" >&2
    printf 'body: %s\n' "$(cat "$BODY_FILE" 2>/dev/null || true)" >&2
    tail -n 120 "$LOG" >&2 || true
    exit 1
fi

grep -q 'user_input' "$BODY_FILE" \
    || { printf 'verify-native-endpoint: 400 body does not mention user_input: %s\n' "$(cat "$BODY_FILE")" >&2; exit 1; }

printf '    GET  /                    -> 200\n'
printf '    POST /api/native/chat     -> 400 (%s)\n' "$(cat "$BODY_FILE")"
printf '==> Native endpoint OK\n'
