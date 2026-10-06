package eu.kanade.tachiyomi.animeextension.en.hexawatch

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Provides the Cap.js proof-of-work token that hexa.su requires (`x-cap-token`) for source requests.
 *
 * The challenge is solved by the site's own Cap.js widget running in a real [WebView] on the
 * `hexa.su` origin. The token is cached in memory and only re-solved when it expires or when the
 * API rejects it.
 */
class CapTokenProvider {

    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    @Volatile
    private var token: String? = null

    @Volatile
    private var expiresAt = 0L

    /**
     * Returns a valid token, solving a new challenge only if none is cached.
     */
    suspend fun getToken(): String = mutex.withLock {
        token?.takeIf { System.currentTimeMillis() < expiresAt } ?: solve().also {
            token = it
            expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
        }
    }

    /**
     * Drops [rejected] from the cache so the next [getToken] call solves a new challenge.
     * A token that was already replaced by a concurrent caller is left untouched.
     */
    fun invalidate(rejected: String) {
        if (token == rejected) {
            token = null
            expiresAt = 0L
        }
    }

    private suspend fun solve(): String {
        val result = CompletableDeferred<String>()
        val webViewRef = AtomicReference<WebView?>()

        handler.post {
            try {
                webViewRef.set(createWebView(result))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        try {
            return withTimeoutOrNull(SOLVE_TIMEOUT_MS) { result.await() }
                ?: throw Exception("Timed out solving the HexaWatch captcha")
        } finally {
            handler.post {
                webViewRef.getAndSet(null)?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(result: CompletableDeferred<String>): WebView {
        val webView = WebView(applicationContext)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        // Cap's instrumentation rejects a focused window whose outer size is 0x0, which is what an
        // off-screen WebView reports until it has been laid out.
        val metrics = applicationContext.resources.displayMetrics
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

        webView.addJavascriptInterface(CapBridge(result), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    result.completeExceptionally(Exception("Captcha page failed to load: ${error?.description}"))
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                result.completeExceptionally(Exception("Captcha WebView process crashed"))
                return true
            }
        }

        webView.loadDataWithBaseURL(PAGE_BASE_URL, PAGE_HTML, "text/html", "utf-8", null)
        return webView
    }

    class CapBridge(private val result: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onToken(token: String) {
            result.complete(token)
        }

        @JavascriptInterface
        fun onError(message: String) {
            result.completeExceptionally(Exception("Captcha failed: $message"))
        }
    }

    private companion object {
        const val BRIDGE_NAME = "HexaCap"

        // Same origin the site's widget runs on, so the Cap server sees the requests it expects.
        const val PAGE_BASE_URL = "https://hexa.su/"
        const val CAP_ENDPOINT = "https://cap.hexa.su/15d2cf0395/"
        const val CAP_WIDGET_URL = "https://cdn.jsdelivr.net/npm/@cap.js/widget"

        const val SOLVE_TIMEOUT_MS = 60_000L

        // The site itself discards its token after 3 hours; refresh a bit earlier.
        const val TOKEN_TTL_MS = 170 * 60 * 1000L

        val DIAG_LITERAL = """"(function(){function hF(s){let h=12345>>>0;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=(h+(h<<1)+(h<<4)+(h<<7)+(h<<8)+(h<<24))>>>0;}return h>>>0;}function hSet(a,v){for(var i=0;i<a.length;i++)if(a[i]===v)return true;return false;}var out=[];(function(){let B=false;try{if (!B) { try { var d = Object.getOwnPropertyDescriptors(navigator); var __wh = 3151209047; for (const k in d) { if (hF(k) === __wh) { B = true; break; } } if (!B) { var p = Object.getPrototypeOf(navigator); while (p && !B) { for (const k of Object.getOwnPropertyNames(p)) { if (hF(k) === __wh) { try { if (navigator[k]) B = true; } catch {} break; } } p = Object.getPrototypeOf(p); } } } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"webdriver_desc\");})();(function(){let B=false;try{if (!B) { try { var wewvx7kbls = [3151209047,1936917971,2077988308,1711589947,1732656462,4087748831,1331036456,876349562,1859091604,3269967343,838542567,802026918,1487301272,649267312,1015694927,630004063,3815299445]; for (const k of Object.getOwnPropertyNames(navigator)) { if (hSet(wewvx7kbls, hF(k))) { B = true; break; } } } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"nav_own_props\");})();(function(){let B=false;try{if (!B) { var pibs24pf6 = [2150257190,1185748456,497666618]; for (const k of Object.getOwnPropertyNames(window)) { for (var pl = 4; pl <= 5; pl++) { if (hSet(pibs24pf6, hF(k.slice(0, pl)))) { B = true; break; } } if (B) break; } }}catch(e){B=true}if(B)out.push(\"win_prefix\");})();(function(){let B=false;try{if (!B) { var om3abxb = [627334460,1863847334,3723168304,1244092863,4140838208,3900323642,3587490944,2347122857,2566361910,2415293306,2023471575,1101126945,334745549,3111269117,1389622548,262942648,1380537904,4001591352,3590638410,2845031139,202846176,3040767914,2565176541,948698636]; for (const k of Object.getOwnPropertyNames(window)) { if (hSet(om3abxb, hF(k))) { B = true; break; } } }}catch(e){B=true}if(B)out.push(\"win_props\");})();(function(){let B=false;try{if (!B) { var d76f = [3613219727,857431427,1775391292,2669401970,926336277,29261381,344525511,207525006,2869494430,2756715940,1913222040,1682380786]; for (const k of Object.getOwnPropertyNames(document)) { if (hSet(d76f, hF(k))) { B = true; break; } } }}catch(e){B=true}if(B)out.push(\"doc_props\");})();(function(){let B=false;try{if (!B) { try { var sizd = [222347213,3151209047,136222619]; var an = document.documentElement.getAttributeNames(); for (const n of an) { for (const t of n.split(/[^a-z]+/i)) { if (t && hSet(sizd, hF(t.toLowerCase()))) { B = true; break; } } if (B) break; } } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"html_attrs\");})();(function(){let B=false;try{if (!B) { try { var qr7nfk01lu = [3226435175,1417472678,1763826461]; var meybuf1 = (new Error()).stack || ''; for (var i = 0; i + 5 <= meybuf1.length; i++) { for (var sl = 5; sl <= 14; sl++) { if (i + sl > meybuf1.length) break; if (hSet(qr7nfk01lu, hF(meybuf1.substr(i, sl)))) { B = true; break; } } if (B) break; } } catch {} }}catch(e){B=true}if(B)out.push(\"stack\");})();(function(){let B=false;try{if (!B) { try { if (typeof window.exposedFn !== 'undefined') { var s = window.exposedFn.toString(); for (var i = 0; i + 19 <= s.length; i++) { if (hF(s.substr(i, 19)) === 2072727632) { B = true; break; } } } } catch {} }}catch(e){B=true}if(B)out.push(\"exposedFn\");})();(function(){let B=false;try{if (!B && typeof window.process !== 'undefined') { try { if (hF(window.process.type || '') === 3260566938 || (window.process.versions && window.process.versions.electron)) B = true; } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"process\");})();(function(){let B=false;try{if (!B) { try { var l3hyc5 = [906144896,1763826461,836336220,4018332346]; var ua = navigator.userAgent || ''; for (const t of ua.split(/[\\s/(),;]/)) { if (t && hSet(l3hyc5, hF(t))) { B = true; break; } } if (!B) { var av = navigator.appVersion || ''; for (const t of av.split(/[\\s/(),;]/)) { if (t && hSet(l3hyc5, hF(t))) { B = true; break; } } } } catch {} }}catch(e){B=true}if(B)out.push(\"ua_tokens\");})();(function(){let B=false;try{if (!B) { try { var c = document.createElement('canvas').getContext('webgl'); if (c) { var v = c.getParameter(c.VENDOR); var r = c.getParameter(c.RENDERER); if (hF(v || '') === 2485347699 && hF(r || '') === 1010568106) B = true; } } catch {} }}catch(e){B=true}if(B)out.push(\"webgl_mesa\");})();(function(){let B=false;try{if (!B) { try { if (document.hasFocus && document.hasFocus() && window.outerWidth === 0 && window.outerHeight === 0) B = true; } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"focus_outer0\");})();(function(){let B=false;try{if (!B) { try { var es = Function.prototype.toString.call(eval); var found = false; for (var i = 0; i + 13 <= es.length; i++) { if (hF(es.substr(i, 13)) === 1553330389) { found = true; break; } } if (!found) B = true; } catch {} }}catch(e){B=true}if(B)out.push(\"eval_native\");})();(function(){let B=false;try{if (!B) { try { if (typeof Function.prototype.bind === 'undefined') B = true; } catch {} }}catch(e){B=true}if(B)out.push(\"bind\");})();(function(){let B=false;try{if (!B) { try { if (window.external && typeof window.external.toString === 'function') { var s = window.external.toString(); for (var i = 0; i + 9 <= s.length; i++) { if (hF(s.substr(i, 9)) === 3886487666) { B = true; break; } } } } catch { B = true; } }}catch(e){B=true}if(B)out.push(\"external\");})();(function(){let B=false;try{if (!B) { try { if (navigator.mimeTypes) { var cdfi7oc5 = Object.getPrototypeOf(navigator.mimeTypes) === MimeTypeArray.prototype; for (var csegklgei = 0; csegklgei < navigator.mimeTypes.length && cdfi7oc5; csegklgei++) { cdfi7oc5 = Object.getPrototypeOf(navigator.mimeTypes[csegklgei]) === MimeType.prototype; } if (!cdfi7oc5) B = true; } } catch {} }}catch(e){B=true}if(B)out.push(\"mimetypes\");})();(function(){let B=false;try{if (!B) { try { var svur = navigator.productSub; var ud3qqdg = navigator.userAgent || ''; if (svur && hF(svur) !== 339447644) { var likeBlink = false; for (const t of ud3qqdg.toLowerCase().split(/[\\s/(),;]/)) { var hh = hF(t); if (hh === 2675064399 || hh === 2739124701 || hh === 4247070848) { likeBlink = true; break; } } if (likeBlink) B = true; } } catch {} }}catch(e){B=true}if(B)out.push(\"productSub\");})();(function(){let B=false;try{if (!B) { try { var g9g5vha4 = Object.getOwnPropertyNames(window); for (const n of g9g5vha4) { var u = n.lastIndexOf('_'); if (u > 3 && u < n.length - 1) { var suf = n.slice(u + 1); var hh = hF(suf); if (hh === 1986932066 || hh === 397831350 || hh === 3744637197) { B = true; break; } } } } catch {} }}catch(e){B=true}if(B)out.push(\"win_suffix\");})();parent.postMessage({type:'hexa-diag',tripped:out.join(',')||'none'},'*');})();""""

        val PAGE_HTML = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body>
            <script>
            (function () {
                // Snapshot of the signals Cap's automation checks read, reported on failure.
                var navOwn = ['webdriver', 'userAgent', 'platform', 'languages', 'plugins', 'deviceMemory'];
                var env = 'outer ' + outerWidth + 'x' + outerHeight +
                    ', inner ' + innerWidth + 'x' + innerHeight +
                    ', focus ' + document.hasFocus() +
                    ', webdriver ' + navigator.webdriver +
                    ', productSub ' + navigator.productSub +
                    ', navOwn [' + Object.getOwnPropertyNames(navigator).filter(function (k) {
                        return navOwn.indexOf(k) >= 0;
                    }) + ']';
                var DIAG = $DIAG_LITERAL;
                var failed = false;
                function fail(message) {
                    if (failed) return;
                    failed = true;
                    var reported = false;
                    function report(tripped) {
                        if (reported) return;
                        reported = true;
                        $BRIDGE_NAME.onError('tripped: ' + tripped + ' | ' + String(message) + ' [' + env + ']');
                    }
                    window.addEventListener('message', function (e) {
                        if (e.data && e.data.type === 'hexa-diag') report(e.data.tripped);
                    });
                    try {
                        var f = document.createElement('iframe');
                        f.setAttribute('sandbox', 'allow-scripts');
                        f.srcdoc = '<!DOCTYPE html><html><head></head><body><script>' + DIAG + '<\/script></body></html>';
                        document.body.appendChild(f);
                    } catch (e) {
                        report('diag error ' + e);
                    }
                    setTimeout(function () { report('diag timeout'); }, 5000);
                }

                // A WebView that is not attached to a window reports a 0x0 outer size, which Cap's
                // instrumentation (run in a srcdoc iframe) treats as an automated browser.
                var shim = '<script>(function () {' +
                    'if (outerWidth && outerHeight) return;' +
                    'var w = screen.width, h = screen.height;' +
                    'Object.defineProperty(window, "outerWidth", { get: function () { return w; }, configurable: true });' +
                    'Object.defineProperty(window, "outerHeight", { get: function () { return h; }, configurable: true });' +
                    '})();<\/script>';
                var srcdoc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'srcdoc');
                Object.defineProperty(HTMLIFrameElement.prototype, 'srcdoc', {
                    configurable: true,
                    enumerable: srcdoc.enumerable,
                    get: function () { return srcdoc.get.call(this); },
                    set: function (value) { srcdoc.set.call(this, String(value).replace('<head>', '<head>' + shim)); },
                });
                var script = document.createElement('script');
                script.src = '$CAP_WIDGET_URL';
                script.onerror = function () { fail('Failed to load the Cap widget'); };
                script.onload = function () {
                    try {
                        var widget = document.createElement('cap-widget');
                        widget.setAttribute('data-cap-api-endpoint', '$CAP_ENDPOINT');
                        widget.setAttribute('data-cap-disable-haptics', '');
                        widget.addEventListener('solve', function (event) {
                            if (event.detail && event.detail.token) {
                                $BRIDGE_NAME.onToken(event.detail.token);
                            }
                        });
                        widget.addEventListener('error', function (event) {
                            var detail = event.detail || {};
                            fail(detail.code ? detail.code + ': ' + detail.message : detail.message || 'Unknown error');
                        });
                        document.body.appendChild(widget);
                        widget.solve().then(function (solution) {
                            if (solution && solution.token) {
                                $BRIDGE_NAME.onToken(solution.token);
                            }
                        }, function (error) {
                            fail(error && error.message ? error.message : error);
                        });
                    } catch (e) {
                        fail(e && e.message ? e.message : e);
                    }
                };
                document.head.appendChild(script);
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
