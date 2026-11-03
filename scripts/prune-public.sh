#!/usr/bin/env bash
# Prune the browser-only assets from a SillyTavern server tree.
#
# public/ cannot be dropped: the native client's headless prompt builder loads
# the real web client from it (index.html, script.js and the whole scripts/
# module graph, plus locales/ and lib/). Only the assets a browser would paint
# are removed - jsdom never reads fonts, images, stylesheets or sounds, and no
# native screen requests them.
#
# Usage: prune-public.sh <server-dir> [--require-bundle]
#
# --require-bundle asserts public/lib.bundle.js is present and non-empty. The
# staging pipeline passes it after copying the webpack output; the parity gate
# does not, because a bare checkout has no compiled bundle (the harness lets
# webpack build it at boot).
set -euo pipefail

die() {
    printf 'prune-public: %s\n' "$*" >&2
    exit 1
}

SERVER_DIR=""
REQUIRE_BUNDLE=0
for arg in "$@"; do
    case "$arg" in
        --require-bundle) REQUIRE_BUNDLE=1 ;;
        --*) die "unknown option: $arg" ;;
        *)
            [[ -z "$SERVER_DIR" ]] || die "unexpected argument: $arg"
            SERVER_DIR="$arg"
            ;;
    esac
done

[[ -n "$SERVER_DIR" ]] || die "usage: $0 <server-dir> [--require-bundle]"
[[ -d "$SERVER_DIR" ]] || die "server dir does not exist: $SERVER_DIR"
PUBLIC_DIR="$SERVER_DIR/public"
[[ -n "$PUBLIC_DIR" ]] || die "refusing to prune an empty public dir"
[[ -f "$PUBLIC_DIR/index.html" ]] || die "public/index.html is missing before pruning"
if (( REQUIRE_BUNDLE )); then
    [[ -s "$PUBLIC_DIR/lib.bundle.js" ]] || die "public/lib.bundle.js is empty before pruning"
fi

rm -rf -- "$PUBLIC_DIR/webfonts" "$PUBLIC_DIR/img" "$PUBLIC_DIR/css" "$PUBLIC_DIR/sounds"
rm -f -- "$PUBLIC_DIR/style.css" "$PUBLIC_DIR"/*.ico

for kept in index.html script.js scripts lib locales; do
    [[ -e "$PUBLIC_DIR/$kept" ]] || die "public/$kept disappeared during pruning"
done
if (( REQUIRE_BUNDLE )); then
    [[ -s "$PUBLIC_DIR/lib.bundle.js" ]] || die "public/lib.bundle.js disappeared during pruning"
fi
