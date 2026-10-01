package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val items = response.useAsJsoup().select(videoListSelector())
        return items.parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            getVideoList(url, name, episodeUrl)
        }
    }

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    private suspend fun getVideoList(url: String, name: String, episodeUrl: String): List<Video> = when {
        // Embed = googlevideo/blogger MP4; Prime = HLS (.image segments via m3u8server)
        "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url, name.trim(), episodeUrl)
        else -> emptyList()
    }
}
