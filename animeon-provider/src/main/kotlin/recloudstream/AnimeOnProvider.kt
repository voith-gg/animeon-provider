package recloudstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.regex.Pattern

class AnimeOnProvider : MainAPI() {
    override var mainUrl = "https://animeon.cc"
    override var name = "AnimeOn"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ========================================================================
    // ГЛАВНАЯ СТРАНИЦА — карточки тайтлов с /
    // ========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val html = app.get("$mainUrl/").text
            val items = extractMainPageItems(html)
            newHomePageResponse(
                listOf(HomePageList("Популярные аниме", items, true)),
                false
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    private fun extractMainPageItems(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href^='/anime/']")) {
            val href = a.attr("href")
            if (href in seen) continue
            val title = a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: continue
            seen.add(href)
            items.add(newMovieSearchResponse(title, "$mainUrl$href", TvType.Anime))
            if (items.size >= 30) break
        }
        return items
    }

    // ========================================================================
    // ПОИСК — GET /api/search?q=QUERY (JSON API)
    // ========================================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return try {
            val json = app.get("$mainUrl/api/search?q=${query.encodeUri()}").text
            val data = tryParseJson<SearchApiResponse>(json)
            val items = data?.results?.mapNotNull { item ->
                val slug = item.anime_url ?: return@mapNotNull null
                val posterFull = item.poster?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                newMovieSearchResponse(item.title, "$mainUrl/anime/$slug", TvType.Anime) {
                    posterUrl = posterFull
                }
            } ?: emptyList()
            newSearchResponseList(items, items.isNotEmpty())
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private data class SearchApiResponse(
        val results: List<SearchApiItem>?
    )

    private data class SearchApiItem(
        val title: String,
        val title_orig: String?,
        val year: Int?,
        val poster: String?,
        val anime_url: String?,
        val kind: String?,
        val rating: Float?,
        val status: String?,
        val votes_count: Int?
    )

    // ========================================================================
    // СТРАНИЦА ТАЙТЛА — список серий (load)
    // Данные лежат в Next.js Flight-стринге, где кавычки экранированы: \"key\":\"value\"
    // ========================================================================
    override suspend fun load(url: String): LoadResponse {
        return try {
            val html = app.get(url).text
            val animeStart = html.indexOf(ANIME_MARKER)
            if (animeStart < 0) throw ErrorLoadingException("Anime block not found")
            val segment = html.substring(animeStart)

            val titleOrig = extractEscapedField(segment, "title_orig")
            val title = extractTitleBeforeOrig(segment) ?: titleOrig ?: "Аниме"
            val yearValue = Regex("\"datePublished\":\"(\\d{4})")
                .find(html)?.groupValues?.get(1)?.toIntOrNull()
            val description = extractMeta(html, "og:description")
                ?: extractMeta(html, "description")
            val posterPath = extractEscapedField(segment, "anime_poster")
            val poster = posterPath?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: extractMeta(html, "og:image")

            val episodes = extractEpisodes(segment)
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes.map { ep ->
                    newEpisode(ep.link) {
                        name = ep.name
                        episode = ep.number
                        posterUrl = poster
                    }
                }
            ) {
                plot = description
                year = yearValue
                posterUrl = poster
                backgroundPosterUrl = poster
                actors = titleOrig?.let { listOf(ActorData(Actor(it, ""))) } ?: emptyList()
            }
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Error loading anime: ${e.message ?: e}")
        }
    }

    private data class EpisodeLink(val number: Int, val name: String, val link: String)

    private fun extractEpisodes(segment: String): List<EpisodeLink> {
        val linkMarker = "\\\"link\\\":\\\""
        val episodes = mutableListOf<EpisodeLink>()
        var idx = 0
        while (true) {
            val p = segment.indexOf(linkMarker, idx)
            if (p < 0) break
            idx = p + linkMarker.length

            val valueStart = p + linkMarker.length
            val valueEnd = segment.indexOf("\\\"", valueStart)
            if (valueEnd < 0) continue
            var link = segment.substring(valueStart, valueEnd)
            if (!link.contains("kodikplayer")) continue
            if (link.startsWith("//")) link = "https:$link"
            else if (!link.startsWith("http")) link = "https://$link"

            // перед ссылкой идёт \"N\":{ — номер серии
            val before = segment.substring(maxOf(0, p - 40), p).replace("\\\"", "\"")
            val number = Regex("\"(\\d+)\":\\{").findAll(before).lastOrNull()
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0

            episodes.add(
                EpisodeLink(
                    number = number,
                    name = if (number > 0) "Серия $number" else "Серия",
                    link = link
                )
            )
        }
        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun extractEscapedField(segment: String, key: String): String? {
        val marker = "\\\"$key\\\":\\\""
        val start = segment.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = segment.indexOf("\\\"", valueStart)
        if (valueEnd < 0) return null
        return segment.substring(valueStart, valueEnd)
    }

    private fun extractTitleBeforeOrig(segment: String): String? {
        val origMarker = "\\\"title_orig\\\":\\\""
        val origIdx = segment.indexOf(origMarker)
        if (origIdx < 0) return null
        val titleMarker = "\\\"title\\\":\\\""
        val tIdx = segment.lastIndexOf(titleMarker, origIdx)
        if (tIdx < 0) return null
        val valueStart = tIdx + titleMarker.length
        val valueEnd = segment.indexOf("\\\"", valueStart)
        if (valueEnd < 0) return null
        return segment.substring(valueStart, valueEnd)
    }

    private fun extractMeta(html: String, name: String): String? {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("meta[property='$name']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name='$name']")?.attr("content")?.takeIf { it.isNotBlank() }
    }

    // ========================================================================
    // ССЫЛКИ НА ВИДЕО (loadLinks)
    // Сложный путь: попытаться вытащить прямой .m3u8/.mp4 со страницы kodikplayer.
    // Легкий fallback: loadExtractor на ссылку kodikplayer.
    // ========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directUrl = try {
            extractDirectVideoUrl(data)
        } catch (e: Exception) {
            logError(e)
            null
        }
        if (directUrl != null) {
            callback(
                newExtractorLink(
                    name,
                    "Видео (kodikplayer)",
                    directUrl
                ) {
                    quality = Qualities.P720.value
                }
            )
            return true
        }
        return loadExtractor(data, subtitleCallback, callback)
    }

    private suspend fun extractDirectVideoUrl(kodikUrl: String): String? {
        val url = if (kodikUrl.startsWith("//")) "https:$kodikUrl" else kodikUrl
        val html = app.get(url).text
        val m3u8 = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").matcher(html)
        if (m3u8.find()) return m3u8.group(0)
        val mp4 = Pattern.compile("https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*").matcher(html)
        if (mp4.find()) return mp4.group(0)
        return null
    }

    companion object {
        // в HTML: {\"anime\":{\"alloha_iframe\":...
        private const val ANIME_MARKER = "\\\"anime\\\":{\\\"alloha_iframe\\\""
    }
}
