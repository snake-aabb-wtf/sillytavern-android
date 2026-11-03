/*
 * Android blob-export bridge for the loopback SillyTavern page.
 *
 * Android's DownloadListener never receives `blob:` URLs, so SillyTavern's export buttons
 * (character card, chat, preset, theme, user backup) do nothing on Android. This script turns
 * a clicked blob download into a data URL and hands it to the native `STBridge.saveBlob`
 * interface, which writes it through the Storage Access Framework.
 *
 * Every call carries `window.__stToken`, injected by MainActivity into the loopback main
 * frame only. Remote cross-origin iframes (YouTube, Maps, ...) cannot read it, so the
 * JavascriptInterface cannot be driven from embedded content.
 *
 * Non-blob links are left untouched so the normal DownloadListener path still applies.
 * The install is idempotent: MainActivity re-injects the asset on every onPageFinished().
 */
(function () {
    'use strict';

    if (window.__stBridgeInstalled) return;

    var bridge = window.STBridge;
    var token = window.__stToken;
    if (!bridge || typeof bridge.saveBlob !== 'function' ||
        typeof token !== 'string' || token.length === 0) {
        return;
    }

    window.__stBridgeInstalled = true;

    var MAX_BYTES = 64 * 1024 * 1024;
    var MAX_RETAINED_BYTES = 128 * 1024 * 1024;
    var MAX_RETAINED = 8;
    var DEFAULT_NAME = 'download.bin';

    /*
     * SillyTavern calls URL.revokeObjectURL() immediately after a.click(). Retaining the
     * Blob behind the URLs we hand out keeps the export alive if the asynchronous fetch
     * loses that race. Bounded by both entry count and total bytes.
     */
    var retained = new Map();
    var retainedBytes = 0;

    function release(url) {
        var blob = retained.get(url);
        if (!blob) return;
        retainedBytes -= blob.size;
        retained.delete(url);
    }

    function trimRetained() {
        while (retained.size > 0 &&
            (retained.size > MAX_RETAINED || retainedBytes > MAX_RETAINED_BYTES)) {
            release(retained.keys().next().value);
        }
    }

    function retain(url, blob) {
        if (blob.size > MAX_BYTES || retained.has(url)) return;
        retained.set(url, blob);
        retainedBytes += blob.size;
        trimRetained();
    }

    var nativeCreateObjectURL = URL.createObjectURL;
    if (typeof nativeCreateObjectURL === 'function') {
        URL.createObjectURL = function (object) {
            var url = nativeCreateObjectURL.call(URL, object);
            if (object instanceof Blob) {
                retain(url, object);
            }
            return url;
        };
    }

    function isBlobUrl(href) {
        return typeof href === 'string' && href.indexOf('blob:') === 0;
    }

    function readAsDataUrl(blob) {
        return new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onload = function () { resolve(String(reader.result || '')); };
            reader.onerror = function () {
                reject(reader.error || new Error('FileReader failed'));
            };
            reader.readAsDataURL(blob);
        });
    }

    function reportTooLarge(name, size) {
        console.warn('[STBridge] export exceeds 64 MB, dropped');
        if (typeof bridge.reportTooLarge === 'function') {
            bridge.reportTooLarge(name, size, token);
        }
    }

    function save(anchor, href) {
        var name = anchor.download || DEFAULT_NAME;
        fetch(href)
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('blob fetch failed: ' + response.status);
                }
                return response.blob();
            })
            .catch(function (error) {
                // URL.revokeObjectURL() may already have run; the retained Blob still works.
                var cached = retained.get(href);
                if (!cached) throw error;
                return cached;
            })
            .then(function (blob) {
                release(href);
                if (blob.size > MAX_BYTES) {
                    reportTooLarge(name, blob.size);
                    return null;
                }
                return readAsDataUrl(blob);
            })
            .then(function (dataUrl) {
                if (!dataUrl) return;
                if (dataUrl.indexOf('data:') !== 0) {
                    throw new Error('unexpected FileReader output');
                }
                bridge.saveBlob(name, dataUrl, token);
            })
            .catch(function (error) {
                release(href);
                console.error('[STBridge] blob export failed', error);
            });
    }

    /**
     * Handles one anchor if it is a blob download.
     * @returns {boolean} true when this click belongs to the bridge.
     */
    function handleAnchor(anchor) {
        if (!anchor || anchor.localName !== 'a' || !anchor.hasAttribute('download')) {
            return false;
        }
        var href = anchor.href || anchor.getAttribute('href') || '';
        if (!isBlobUrl(href)) return false; // http(s) stays with DownloadListener
        save(anchor, href);
        return true;
    }

    // Capture phase: run before SillyTavern's own handlers and cancel the WebView's
    // dead-end attempt to download a blob: URL.
    document.addEventListener('click', function (event) {
        var target = event.target;
        var anchor = target && typeof target.closest === 'function'
            ? target.closest('a[download]')
            : null;
        if (!anchor) return;
        if (handleAnchor(anchor)) {
            event.preventDefault();
        }
    }, true);

    /*
     * Fallback for detached anchors. SillyTavern's download() helper creates the anchor,
     * clicks it and never inserts it into the document, so that click never reaches the
     * document listener above.
     */
    var nativeClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (!this.isConnected && handleAnchor(this)) return;
        return nativeClick.apply(this, arguments);
    };
})();
