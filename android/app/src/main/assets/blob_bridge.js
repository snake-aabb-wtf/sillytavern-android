/*
 * Android blob-export bridge for the loopback SillyTavern page.
 *
 * Android's DownloadListener never receives `blob:` URLs, so SillyTavern's export buttons
 * (character card, chat, preset, theme, user backup) do nothing on Android. This script turns
 * a clicked blob download into a data URL and hands it to the native `STBridge.saveBlob`
 * interface, which writes it through the Storage Access Framework.
 *
 * Non-blob links are left untouched so the normal DownloadListener path still applies.
 * The install is idempotent: MainActivity re-injects the asset on every onPageFinished().
 */
(function () {
    'use strict';

    if (window.__stBridgeInstalled) return;

    var bridge = window.STBridge;
    if (!bridge || typeof bridge.saveBlob !== 'function') return;

    window.__stBridgeInstalled = true;

    var MAX_BYTES = 64 * 1024 * 1024;
    var DEFAULT_NAME = 'download.bin';
    var MAX_RETAINED = 8;

    /*
     * SillyTavern calls URL.revokeObjectURL() immediately after a.click(). Retaining the
     * Blob behind the URLs we hand out keeps the export alive if the asynchronous fetch
     * loses that race.
     */
    var retained = new Map();
    var nativeCreateObjectURL = URL.createObjectURL;
    if (typeof nativeCreateObjectURL === 'function') {
        URL.createObjectURL = function (object) {
            var url = nativeCreateObjectURL.call(URL, object);
            if (object instanceof Blob && object.size <= MAX_BYTES) {
                retained.set(url, object);
                while (retained.size > MAX_RETAINED) {
                    retained.delete(retained.keys().next().value);
                }
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

    function save(anchor, href) {
        var name = anchor.download || DEFAULT_NAME;
        var blobPromise = fetch(href)
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
            });

        blobPromise
            .then(function (blob) {
                retained.delete(href);
                if (blob.size > MAX_BYTES) {
                    console.warn('[STBridge] export exceeds 64 MB, dropped');
                    return null;
                }
                return readAsDataUrl(blob);
            })
            .then(function (dataUrl) {
                if (!dataUrl) return;
                if (dataUrl.indexOf('data:') !== 0) {
                    throw new Error('unexpected FileReader output');
                }
                bridge.saveBlob(name, dataUrl);
            })
            .catch(function (error) {
                retained.delete(href);
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
