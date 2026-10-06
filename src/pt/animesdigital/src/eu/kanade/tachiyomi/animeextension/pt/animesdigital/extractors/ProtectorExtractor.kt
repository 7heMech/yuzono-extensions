package eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class ProtectorExtractor(private val client: OkHttpClient, private val headers: Headers) {
    /**
     * Follows the ad-protected player link (campaign page → meta refresh → article page)
     * and returns the embed URL the article reveals after its countdown.
     */
    suspend fun embedUrlFromUrl(url: String): String? {
        var nextUrl = if (url.startsWith("//")) "https:$url" else url
        repeat(MAX_HOPS) {
            val document = client.newCall(GET(nextUrl, headers)).awaitSuccess().useAsJsoup()
            document.selectFirst("[data-url]")?.absUrl("data-url")?.takeIf(String::isNotEmpty)
                ?.let { return it }
            nextUrl = document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.substringAfter("url=", "")?.takeIf(String::isNotEmpty)
                ?: return null
        }
        return null
    }

    companion object {
        private const val MAX_HOPS = 4
    }
}
