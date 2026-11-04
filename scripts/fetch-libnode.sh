#!/usr/bin/env bash
# Fetch the ICU-enabled libnode used by the Android build.
#
# Usage:
#   bash scripts/fetch-libnode.sh          # skip if a valid copy is present
#   FORCE=1 bash scripts/fetch-libnode.sh  # always download
#
# Produces android/nodejs-mobile/libnode/{include/node, bin/arm64-v8a/libnode.so}.
# The npm package's libnode.so is built with --with-intl=none, so only its
# headers are reused; the .so comes from the project's Release asset
# (libnode-intl-18.20.4 by default). The asset digest is checked when GitHub
# reports one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBNODE_DIR="$ROOT/android/nodejs-mobile/libnode"
SO="$LIBNODE_DIR/bin/arm64-v8a/libnode.so"
TAG="${LIBNODE_TAG:-libnode-intl-18.20.4}"
NJM_VERSION="${NODEJS_MOBILE_VERSION:-18.20.4}"
FORCE="${FORCE:-0}"

die() {
    printf 'fetch-libnode: %s\n' "$*" >&2
    exit 1
}

for tool in curl tar strings sha256sum python3; do
    command -v "$tool" >/dev/null 2>&1 || die "required tool not found: $tool"
done

REPO="${GITHUB_REPOSITORY:-}"
if [[ -z "$REPO" ]]; then
    REPO="$(git -C "$ROOT" remote get-url origin 2>/dev/null \
        | sed -E 's#.*github\.com[:/]([^/]+/[^/.]+)(\.git)?$#\1#')"
fi
[[ -n "$REPO" && "$REPO" == */* ]] || die "cannot determine the GitHub repository; set GITHUB_REPOSITORY=owner/repo"

# Optional, used only to avoid unauthenticated API rate limits.
auth_args=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    auth_args=(-H "Authorization: Bearer $GITHUB_TOKEN")
elif [[ -n "${GH_TOKEN:-}" ]]; then
    auth_args=(-H "Authorization: Bearer $GH_TOKEN")
fi

asset_sha=""
if [[ -z "${SKIP_DIGEST:-}" ]]; then
    asset_sha="$(curl -fsSL --retry 3 "${auth_args[@]}" \
        -H 'Accept: application/vnd.github+json' \
        "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); a=next((x for x in d.get("assets",[]) if x.get("name")=="libnode.so"), {}); s=(a.get("digest") or ""); print(s[7:] if s.startswith("sha256:") else "")' \
        2>/dev/null || true)"
fi

valid_cached() {
    [[ -f "$LIBNODE_DIR/include/node/node.h" && -s "$SO" ]] || return 1
    if [[ -n "$asset_sha" ]] && ! echo "$asset_sha  $SO" | sha256sum -c - >/dev/null 2>&1; then
        return 1
    fi
    strings -a "$SO" | grep -q icudt || return 1
    strings -a "$SO" | grep -q 'Intl' || return 1
    return 0
}

if [[ "$FORCE" != "1" ]] && valid_cached; then
    printf 'fetch-libnode: %s is present and valid; nothing to do\n' "$SO"
    exit 0
fi

printf '==> Fetching nodejs-mobile headers (%s)\n' "$NJM_VERSION"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp/njm.tgz" \
    "https://registry.npmjs.org/nodejs-mobile-react-native/-/nodejs-mobile-react-native-$NJM_VERSION.tgz"
tar -C "$tmp" -xzf "$tmp/njm.tgz"
mkdir -p "$LIBNODE_DIR/bin/arm64-v8a"
rm -rf -- "$LIBNODE_DIR/include"
cp -a "$tmp/package/android/libnode/include" "$LIBNODE_DIR/"

printf '==> Downloading %s\n' "$TAG/libnode.so"
curl -fSL --retry 3 -o "$SO" \
    "https://github.com/$REPO/releases/download/$TAG/libnode.so"

if [[ -n "$asset_sha" ]]; then
    echo "$asset_sha  $SO" | sha256sum -c -
else
    printf 'fetch-libnode: WARNING: no published sha256 digest; skipped checksum\n' >&2
fi

[[ -s "$LIBNODE_DIR/include/node/node.h" ]] || die "node headers are missing"
[[ -s "$SO" ]] || die "libnode.so is missing"
[[ "$(stat -c %s "$SO")" -gt 41943040 ]] || die "libnode.so is suspiciously small: $(stat -c %s "$SO") bytes"
strings -a "$SO" > "$tmp/strings.txt"
grep -q icudt "$tmp/strings.txt" || die "libnode.so has no ICU data (wrong asset?)"
grep -q 'Intl' "$tmp/strings.txt" || die "libnode.so has no Intl (wrong asset?)"
printf '==> OK: %s (%s)\n' "$SO" "$(du -h "$SO" | cut -f1)"
