package eu.kanade.tachiyomi.animeextension.bg.filmifen

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID

internal class YoutubeExtractor(private val client: OkHttpClient, headers: Headers) {
    private val youtubeHeaders = headers.newBuilder()
        .set("Referer", "https://www.youtube.com/")
        .set("X-Goog-Api-Format-Version", "2")
        .build()
    private val playlistUtils = PlaylistUtils(client, youtubeHeaders)

    suspend fun videosFromUrl(url: String): List<Video> {
        val httpUrl = url.toHttpUrl()
        val videoId = httpUrl.queryParameter("v") ?: httpUrl.pathSegments.last()
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
                "gl" to "BG",
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
            "YouTube: ${player.playabilityStatus.reason ?: "Трейлърът не е достъпен"}"
        }
        val streamingData = player.streamingData ?: error("YouTube: видеото не е намерено")
        streamingData.hlsManifestUrl?.let {
            return playlistUtils.extractFromHls(it, referer = "https://www.youtube.com/", videoNameGen = { quality -> "YouTube - $quality" })
        }
        return streamingData.formats.mapNotNull { format ->
            val videoUrl = format.url ?: return@mapNotNull null
            if (!format.mimeType.startsWith("video/")) return@mapNotNull null
            Video(videoUrl = videoUrl, videoTitle = "YouTube - ${format.qualityLabel ?: "Трейлър"}", headers = youtubeHeaders)
        }.also { check(it.isNotEmpty()) { "YouTube: видеото не е намерено" } }
    }
}
