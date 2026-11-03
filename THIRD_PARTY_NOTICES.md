# Third-party notices

The released APK bundles the components below. Full licence texts ship with each
component in this repository or in the corresponding package; this file records
what is included and where its licence lives.

## Runtime inside the APK

| Component | Version | Licence | Notes |
|---|---|---|---|
| SillyTavern | 1.19.0 (`06bde939f`) | AGPL-3.0 | `SillyTavern/LICENSE` |
| Node.js (nodejs-mobile) | 18.20.4 | MIT | `libnode.so` built from source; Node's `LICENSE` covers bundled deps |
| nodejs-mobile | 18.20.4 | MIT | Android port used to build `libnode.so` |
| V8 | as shipped with Node 18.20.4 | BSD-3-Clause | inside `libnode.so` |
| ICU (small-icu) | 74 | Unicode License V3 | data embedded in `libnode.so` |
| OpenSSL | 3.x (Node 18.20.4) | Apache-2.0 | inside `libnode.so` |
| libuv, nghttp2, c-ares, zlib | as shipped with Node 18.20.4 | MIT / MIT / MIT / Zlib | inside `libnode.so` |

`assets/runtime.zip` also contains the npm production dependencies of
SillyTavern. Their licence files are preserved in the `node_modules` tree inside
the zip and in `SillyTavern/node_modules` when dependencies are installed.

## Android host

| Component | Licence |
|---|---|
| AndroidX Core / Activity / Lifecycle | Apache-2.0 |
| Kotlin standard library | Apache-2.0 |
| Android NDK / platform libraries | Apache-2.0 (NDK), bionic (BSD-style) |

## Notes

- The APK is signed with a committed **debug** keystore. It is not a Play Store
  release key; treat it as a development build.
- `libnode-intl-18.20.4` is built by `.github/workflows/libnode-intl.yml` from
  the nodejs-mobile v18.20.4 source with `--with-intl=small-icu`, and is
  published as a Release asset in this repository.
- If you redistribute a modified APK, keep `LICENSE`, `NOTICE` and this file
  with it, and publish the corresponding source under AGPL-3.0.
