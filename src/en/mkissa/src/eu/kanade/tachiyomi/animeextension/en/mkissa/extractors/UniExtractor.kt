package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// uns.bio player ("Uni"). The player API answers with hex-encoded AES-CBC JSON listing one HLS
// playlist per delivery network; `r` must be the host of the page embedding the player.
class UniExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, embedderHost: String): List<Video> {
        val playerUrl = url.toHttpUrl()
        val origin = "${playerUrl.scheme}://${playerUrl.host}"
        val videoId = playerUrl.fragment?.substringBefore("&")?.takeIf(String::isNotEmpty) ?: return emptyList()

        val playerHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .build()

        val apiUrl = "$origin/api/v1/video".toHttpUrl().newBuilder()
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .addQueryParameter("r", embedderHost)
            .build()

        val payload = client.get(apiUrl, playerHeaders).bodyString().trim()
        val streams = decrypt(payload).parseAs<UniStreams>()

        val config = parseStreamingConfig(streams.streamingConfig)
        val cloudflarePath = streams.cfNative?.takeIf(String::isNotBlank) ?: streams.cf
        val paths = mapOf(
            "Google" to streams.hlsVideoGoogle,
            "Cloudflare" to cloudflarePath,
            "In-House" to streams.source,
            "Tiktok" to streams.hlsVideoTiktok,
        )

        val configuredOrder = config.order.map(::normalizeNetwork).filter { it in paths }
        val fallbackOrder = listOf("Google", "Cloudflare", "In-House", "Tiktok")
        val networkOrder = (configuredOrder + fallbackOrder).distinct()

        for (network in networkOrder) {
            val path = paths[network]?.takeIf(String::isNotBlank) ?: continue
            val adjustment = config.adjust[network]
                ?: config.adjust.entries.firstOrNull { normalizeNetwork(it.key) == network }?.value
            if (adjustment?.disabled == true) continue

            val videos = runCatching {
                val resolvedUrl = resolve(origin, path).toHttpUrl()
                val playlistUrl = resolvedUrl.newBuilder().apply {
                    (adjustment?.params as? JsonObject)?.forEach { (key, value) ->
                        (value as? JsonPrimitive)?.contentOrNull?.let { setQueryParameter(key, it) }
                    }
                    adjustment?.domain?.takeIf(String::isNotBlank)?.let { domain ->
                        encodedPath(resolvedUrl.encodedPath.replace("/hls/", "/hlsmod/$domain/"))
                    }
                }.build().toString()

                val extracted = playlistUtils.extractFromHls(
                    playlistUrl,
                    referer = "$origin/",
                    masterHeaders = playerHeaders,
                    videoHeaders = playerHeaders,
                    videoNameGen = { quality -> "Uni - $quality" },
                )
                if (network == "Tiktok") extracted.map(::forceMpegTs) else extracted
            }.getOrDefault(emptyList())

            if (videos.isNotEmpty()) {
                return videos
            }
        }

        return emptyList()
    }

    private fun normalizeNetwork(name: String): String = when (name.lowercase()) {
        "google", "hlsvideogoogle" -> "Google"
        "cloudflare", "cf", "cfnative" -> "Cloudflare"
        "in-house", "source" -> "In-House"
        "tiktok", "hlsvideotiktok" -> "Tiktok"
        else -> name
    }

    private fun parseStreamingConfig(element: JsonElement?): StreamingConfig {
        if (element == null) return StreamingConfig()
        return runCatching {
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.parseAs<StreamingConfig>()
                is JsonObject -> element.toString().parseAs<StreamingConfig>()
                else -> null
            }
        }.getOrNull() ?: StreamingConfig()
    }

    // Segments from the TikTok network only play with the container forced to MPEG-TS.
    private fun forceMpegTs(video: Video): Video = video.copy(
        mpvArgs = video.mpvArgs.filterNot { it.first == "demuxer-lavf-o" } +
            ("demuxer-lavf-o" to "force_mpegts=1"),
        ffmpegStreamArgs = video.ffmpegStreamArgs.filterNot { it.first == "force_mpegts" } +
            ("force_mpegts" to "1"),
    )

    private fun resolve(origin: String, path: String): String = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("http") -> path
        else -> origin + "/" + path.removePrefix("/")
    }

    private fun decrypt(hex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }

    @Serializable
    class UniStreams(
        val hlsVideoTiktok: String? = null,
        val hlsVideoGoogle: String? = null,
        val cf: String? = null,
        val cfNative: String? = null,
        val source: String? = null,
        val streamingConfig: JsonElement? = null,
    )

    @Serializable
    private class StreamingConfig(
        val order: List<String> = listOf("Google", "Cloudflare", "In-House"),
        val adjust: Map<String, NetworkAdjustment> = emptyMap(),
    )

    @Serializable
    private class NetworkAdjustment(
        val disabled: Boolean = false,
        val domain: String? = null,
        val params: JsonElement? = null,
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }
}
