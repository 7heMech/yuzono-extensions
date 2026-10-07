package aniyomi.lib.youtubeextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.playlistutils.formatCodecs
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Extracts YouTube HLS, progressive and adaptive streams, preserving separate audio tracks.
 * @param client HTTP client used for visitor, player and playlist requests.
 * @param headers Caller headers; the caller's User-Agent is preserved.
 * @param country Country code supplied to the YouTube player API.
 */
class YoutubeExtractor(private val client: OkHttpClient, headers: Headers = Headers.EMPTY, private val country: String = "US") {
    private val youtubeHeaders = headers.newBuilder()
        .set("Referer", "https://www.youtube.com/")
        .set("X-Goog-Api-Format-Version", "2")
        .build()
    private val playlistUtils = PlaylistUtils(client, youtubeHeaders)

    /** Returns codec-labelled streams from a watch, embed, shorts or youtu.be URL. */
    suspend fun videosFromUrl(url: String, prefix: String = "YouTube"): List<Video> {
        val httpUrl = url.toHttpUrl()
        val videoId = httpUrl.queryParameter("v") ?: httpUrl.pathSegments.last { it.isNotEmpty() }
        val context = YoutubeContext(
            client = mapOf(
                "clientName" to "VISIONOS",
                "clientVersion" to "1.04",
                "clientScreen" to "WATCH",
                "platform" to "MOBILE",
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.6.0.23O770",
                "hl" to "en",
                "gl" to country,
            ),
        )
        // YouTube requires visitor data before requesting the VISIONOS HLS streams.
        // https://github.com/TeamNewPipe/NewPipeExtractor/blob/65cabc2ba5216ee871ace4a9963c08bdbf5d5dc0/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeStreamHelper.java
        val visitor = client.post(
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
            youtubeHeaders,
            YoutubeVisitorRequest(context).toJsonRequestBody(),
        ).parseAs<YoutubeVisitorResponse>().responseContext.visitorData
        val playerUrl = "https://youtubei.googleapis.com/youtubei/v1/player".toHttpUrl().newBuilder()
            .addQueryParameter("prettyPrint", "false")
            .addQueryParameter("t", UUID.randomUUID().toString().replace("-", "").take(12))
            .addQueryParameter("id", videoId)
            .build()
        val player = client.post(
            playerUrl,
            youtubeHeaders,
            YoutubePlayerRequest(
                context = YoutubeContext(context.client + ("visitorData" to visitor)),
                videoId = videoId,
                cpn = UUID.randomUUID().toString().replace("-", "").take(16),
                contentCheckOk = true,
                racyCheckOk = true,
            ).toJsonRequestBody(),
        ).parseAs<YoutubePlayerResponse>()
        check(player.playabilityStatus.status == "OK") {
            "YouTube: ${player.playabilityStatus.reason ?: "Video is unavailable"}"
        }
        val streamingData = player.streamingData ?: error("YouTube: No playable streams found")
        val audioTracks = streamingData.adaptiveFormats
            .filter { it.url != null && it.mimeType.startsWith("audio/") }
            .sortedWith(compareByDescending<YoutubeFormat> { it.audioTrack?.audioIsDefault == true }.thenByDescending { it.bitrate })
            .distinctBy { it.audioTrack?.id to it.codecs() }
            .map { format ->
                val name = format.audioTrack?.displayName ?: "Audio"
                Track(format.url!!, "$name (${format.codecs()})")
            }
        // Prefer direct streams: packed HLS audio exposes ID3 timestamp metadata that
        // mpv-android's JSON writer serializes with unescaped backslashes.
        val adaptive = if (audioTracks.isNotEmpty()) {
            streamingData.adaptiveFormats.filter { it.mimeType.startsWith("video/") && it.url != null }
        } else {
            emptyList()
        }
        val formats = adaptive.ifEmpty {
            streamingData.formats.filter { it.mimeType.startsWith("video/") && it.url != null }
        }
        val directVideos = formats.sortedWith(compareByDescending<YoutubeFormat> { it.height }.thenByDescending { it.bitrate })
            .map { format ->
                val quality = format.height?.let { "${it}p" } ?: format.qualityLabel ?: "Video"
                val fps = format.fps?.let { " - $it fps" }.orEmpty()
                Video(
                    videoUrl = format.url!!,
                    videoTitle = "$prefix - $quality - ${format.codecs()}$fps",
                    resolution = format.height,
                    bitrate = format.bitrate,
                    headers = youtubeHeaders,
                    audioTracks = if (adaptive.isNotEmpty()) audioTracks else emptyList(),
                )
            }
            .distinctBy(Video::videoTitle)
        if (directVideos.isNotEmpty()) return directVideos
        streamingData.hlsManifestUrl?.let {
            return playlistUtils.extractFromHlsWithDetails(
                it,
                referer = "https://www.youtube.com/",
                videoNameGen = { quality -> "$prefix - $quality" },
            ).map { video ->
                if (video.audioTracks.isEmpty()) video.copy(audioTracks = audioTracks) else video
            }.distinctBy(Video::videoTitle)
        }
        error("YouTube: No playable streams found")
    }

    private fun YoutubeFormat.codecs(): String = formatCodecs(mimeType.substringAfter("codecs=\"", "").substringBefore('"'))
        .ifEmpty { mimeType.substringBefore(';').substringAfter('/') }
}
