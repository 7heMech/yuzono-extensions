package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MeusAnimes : AnimeHttpLegacySource() {

    override val name = "Meus Animes"
    override val baseUrl = "https://meusanimes.blog"
    override val lang = "pt-BR"
    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/a/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div#archive-content article.item").map(::animeFromElement)
        val hasNextPage = document.selectFirst("div.pagination a.arrow_pag") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun animeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("div.data h3 a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("div.poster img")?.absUrl("src")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("page/$page/")
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.result-item article").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("div.title a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = element.selectFirst("div.thumbnail img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("div.pagination a.arrow_pag") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("div.sheader h1")!!.text().removeSuffix(" Online")
            thumbnail_url = document.selectFirst("div.sheader div.poster img")?.absUrl("src")
            description = document.select("div.wp-content p").text().ifEmpty { null }

            val genres = document.select("div.sgeneros a").map { it.text() }
            genre = genres.filterNot { it.startsWith("Letra ") }.joinToString().ifEmpty { null }
            status = when {
                genres.any { it.equals("Em Lançamento", ignoreCase = true) } -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val multipleSeasons = document.select("div.se-c").size > 1

        return document.select("ul.episodios li").map { element ->
            SEpisode.create().apply {
                val link = element.selectFirst("div.episodiotitle a")!!
                setUrlWithoutDomain(link.absUrl("href"))

                val numbering = element.selectFirst("div.numerando")?.text().orEmpty()
                val season = numbering.substringBefore(" - ")
                val number = numbering.substringAfter(" - ")

                name = if (multipleSeasons && season.isNotEmpty()) "T$season ${link.text()}" else link.text()
                episode_number = number.toFloatOrNull() ?: 0F
                date_upload = dateFormat.tryParse(element.selectFirst("span.date")?.text())
            }
        }.reversed()
    }

    // ============================== Video Links ===========================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().useAsJsoup()
        val playerUrl = document.selectFirst("div.play-box-iframe iframe")?.absUrl("src")
            ?: return emptyList()

        val (tmdb, season, number) = playerUrl.substringAfter("#/video/", "")
            .trim('/')
            .split('/')
            .takeIf { it.size == 3 }
            ?: return emptyList()

        val apiUrl = playerUrl.toHttpUrl().newBuilder()
            .encodedPath("/posts/get-video.php")
            .fragment(null)
            .addQueryParameter("episode_number", number)
            .addQueryParameter("season_number", season)
            .addQueryParameter("tmdb", tmdb)
            .build()

        val result = client.newCall(GET(apiUrl, headers)).awaitSuccess().parseAs<VideoResponse>()
        if (!result.success) return emptyList()

        return when (val source = result.videoUrl) {
            is JsonPrimitive -> videosFromUrl(source.content)
            is JsonArray -> source.map { it.parseAs<VideoSource>() }
                .map { Video(it.file, it.label, it.file, headers) }
            else -> emptyList()
        }
    }

    private suspend fun videosFromUrl(url: String): List<Video> = when {
        "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
        else -> emptyList()
    }

    // ============================= Utilities ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    companion object {
        private val dateFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
    }
}
