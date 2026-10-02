package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponseDto
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

class AnimeFireExtractor(private val client: OkHttpClient) {

    fun videoListFromElement(videoElement: Element, headers: Headers): List<Video> {
        val jsonUrl = videoElement.attr("data-video-src")
        val responseDto = client.newCall(GET(jsonUrl)).execute().parseAs<AFResponseDto>()
        return responseDto.videos.map {
            val url = it.url.replace("\\", "")
            Video(url, it.quality, url, headers = headers)
        }
    }
}
