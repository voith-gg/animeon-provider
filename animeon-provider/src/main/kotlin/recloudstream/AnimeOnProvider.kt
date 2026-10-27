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
import com.lagradost.cloudstream3.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.tryParseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.regex.Pattern

class AnimeOnProvider : MainAPI() {
    override var mainUrl = "https://animeon.cc"
    override var name = "AnimeOn"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ========================================================================
    // ГЛАВНАЯ СТРАНИЦА — популярные тайтлы с /
    // ========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val html = app.get("$mainUrl/").text
            val items = extractMainPageItems(html)
            newHomePageResponse(
                listOf(HomePageList("Популярные аниме", items, true)),
                page < 2
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    private fun extractMainPageItems(html: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val pattern = Pattern.compile("""href="(/anime/[^"]+)"""")
        val matcher = pattern.matcher(html)
        val seen = mutableSetOf<String>()
        while (matcher.find() && items.size < 30) {
            val slug = matcher.group(1)
            if (slug in seen) continue
            seen.add(slug)
            val title = slug.substringAfterLast("/").replace("-", " ").capitalize()
            items.add(
                newMovieSearchResponse(title, "$mainUrl$slug", TvType.Anime) {}
            )
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
            val items = data?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
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
    ) {
        fun toSearchResponse(): SearchResponse? {
            val url = anime_url?.let { "$mainUrl/anime/$it" } ?: return null
            val posterFull = poster?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            return newMovieSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterFull
                this.plot = title_orig
                this.year = year
                this.rating = rating
            }
        }
    }

    // ========================================================================
    // СТРАНИЦА ТАЙТЛА — список серий (load)
    // HTML страницы содержит встроенный JSON объекта anime с translations
    // ========================================================================
    override suspend fun load(url: String): LoadResponse {
        return try {
            val html = app.get(url).text
            val animeBlock = extractAnimeBlock(html) ?: throw ErrorLoadingException("Anime block not found")

            val title = extractJsonField(animeBlock, "title") ?: "Аниме"
            val titleOrig = extractJsonField(animeBlock, "title_orig")
            val yearStr = extractJsonField(animeBlock, "datePublished")?.take(4)
            val year = yearStr?.toIntOrNull()
            val description = extractJsonField(animeBlock, "description")
            val poster = extractJsonField(animeBlock, "anime_poster")
            val posterFull = poster?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }.orEmpty()

            val episodes = extractEpisodes(animeBlock)
            if (episodes.isEmpty()) {
                throw ErrorLoadingException("No episodes found")
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes.mapIndexed { index, ep ->
                    newEpisode(ep.link) {
                        name = ep.name
                        episode = index + 1
                        posterUrl = posterFull
                    }
                }
            ) {
                plot = description
                year = year
                posterUrl = posterFull
                backgroundPosterUrl = posterFull
                actors = titleOrig?.let {
                    listOf(ActorData(Actor(it, "")))
                } ?: emptyList()
            }
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Error loading anime: ${e.message ?: e}")
        }
    }

    private data class EpisodeLink(val name: String, val link: String)

    private fun extractEpisodes(json: String): List<EpisodeLink> {
        val episodes = mutableListOf<EpisodeLink>()
        // "1":{"link":"//kodikplayer.com/seria/..."}
        val pattern = Pattern.compile("\"(\\d+)\":\\{\\s*\"link\":\"([^\"]+)\"")
        val matcher = pattern.matcher(json)
        while (matcher.find()) {
            val num = matcher.group(1).toIntOrNull() ?: 0
            var link = matcher.group(2)
            if (link.startsWith("//")) link = "https:$link"
            episodes.add(EpisodeLink("Серия $num", link))
        }
        return episodes.sortedBy { it.name }
    }

    private fun extractAnimeBlock(html: String): String? {
        val marker = "\"anime\":{"
        val start = html.indexOf(marker)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (inString) {
                if (c == '"') inString = false
                continue
            }
            if (c == '"') inString = true
            else if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractJsonField(json: String, key: String): String? {
        val pattern = Pattern.compile("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        val matcher = pattern.matcher(json)
        return if (matcher.find()) matcher.group(1) else null
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
        val videoUrl = data
        val directUrl = try {
            extractDirectVideoUrl(videoUrl)
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
        // Fallback: отдаём ссылку kodikplayer как есть
        return loadExtractor(videoUrl, subtitleCallback, callback)
    }

    private fun extractDirectVideoUrl(kodikUrl: String): String? {
        val url = if (kodikUrl.startsWith("//")) "https:$kodikUrl" else kodikUrl
        val html = app.get(url).text
        val m3u8 = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").matcher(html)
        if (m3u8.find()) return m3u8.group(0)
        val mp4 = Pattern.compile("https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*").matcher(html)
        if (mp4.find()) return mp4.group(0)
        return null
    }
}
