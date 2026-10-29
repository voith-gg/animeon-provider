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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.base64Decode
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jsoup.Jsoup

class AnimeOnProvider : MainAPI() {
    override var mainUrl = "https://animeon.cc"
    override var name = "AnimeOn"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)
    override val mainPage = mainPageOf("popular" to "Популярные аниме")

    // ========================================================================
    // ГЛАВНАЯ СТРАНИЦА — карточки тайтлов с /
    // ========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = getHtml("$mainUrl/")
        val items = extractMainPageItems(html)
        if (items.isEmpty()) {
            throw ErrorLoadingException("AnimeOn: на главной странице не найдены карточки")
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name.ifBlank { "Популярные аниме" }, items, true)),
            false
        )
    }

    private suspend fun getHtml(url: String, referer: String? = null): String {
        val response = app.get(
            url,
            headers = BROWSER_HEADERS,
            referer = referer ?: "$mainUrl/"
        )
        if (response.code == 403 || response.code == 503) {
            throw ErrorLoadingException("AnimeOn: HTTP ${response.code} (возможно, Cloudflare)")
        }
        val html = response.text
        if (html.contains("Just a moment...") ||
            html.contains("cf-chl-") ||
            html.contains("Attention Required! | Cloudflare")
        ) {
            throw ErrorLoadingException("AnimeOn: Cloudflare challenge")
        }
        return html
    }

    private fun extractMainPageItems(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        fun addItem(title: String, href: String, poster: String?) {
            if (title.isBlank() || href.isBlank()) return
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "$mainUrl$href"
                else -> "$mainUrl/$href"
            }
            if (!seen.add(url)) return
            items.add(newMovieSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
            })
        }

        // Актуальная разметка сайта: <a> -> ... -> h3.aon-card-title
        for (h3 in doc.select("h3.aon-card-title")) {
            val a = h3.closest("a[href]") ?: continue
            val poster = a.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            }
            addItem(h3.text(), a.attr("href"), poster)
            if (items.size >= 30) break
        }

        // Запасной вариант для старой/альтернативной разметки
        if (items.isEmpty()) {
            for (a in doc.select("a[href*='/anime/']")) {
                val title = a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: continue
                addItem(title, a.attr("href"), a.selectFirst("img")?.attr("src"))
                if (items.size >= 30) break
            }
        }

        return items
    }

    // ========================================================================
    // ПОИСК — GET /api/search?q=QUERY (JSON API)
    // ========================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val response = app.get(
                "$mainUrl/api/search?q=${query.encodeUri()}",
                headers = API_HEADERS,
                referer = "$mainUrl/"
            )
            if (response.code !in 200..299) {
                throw ErrorLoadingException("AnimeOn API: HTTP ${response.code}")
            }
            val data = tryParseJson<SearchApiResponse>(response.text)
            data?.results?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val slug = item.anime_url ?: return@mapNotNull null
                val posterFull = item.poster?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                newMovieSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    posterUrl = posterFull
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchApiResponse(
        val results: List<SearchApiItem>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchApiItem(
        val title: String?,
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
            val html = getHtml(url)
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
            val before = segment.substring(maxOf(0, p - 200), p).replace("\\\"", "\"")
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
        return episodes
            .distinctBy { if (it.number > 0) "n${it.number}" else "l${it.link}" }
            .sortedBy { it.number }
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
    // Сложный путь: kodikplayer -> POST /ftor -> расшифровка ссылок (Caesar + Base64).
    // Легкий fallback: loadExtractor по ссылке kodikplayer.
    // ========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = try {
            extractKodikLinks(data)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }

        if (links.isNotEmpty()) {
            for ((quality, link) in links) {
                val linkName = if (quality > 0) "Kodik ${quality}p" else "Kodik"
                callback(
                    newExtractorLink(
                        name,
                        linkName,
                        link
                    ) {
                        this.quality = quality
                        this.referer = "https://kodikplayer.com/"
                    }
                )
            }
            return true
        }

        return loadExtractor(data, subtitleCallback, callback)
    }

    private suspend fun extractKodikLinks(kodikUrl: String): List<Pair<Int, String>> {
        val url = normalizeUrl(kodikUrl)
        val html = getHtml(url)

        val paramsRaw = Regex("var urlParams = '(\\{.*?\\})';", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val domain = extractJsVar(html, "domain") ?: "kodikplayer.com"
        val type = extractVInfo(html, "type") ?: extractJsVar(html, "type") ?: "seria"
        val id = extractVInfo(html, "id") ?: return emptyList()
        val hash = extractVInfo(html, "hash") ?: return emptyList()

        val dSign = extractJsonValue(paramsRaw, "d_sign") ?: return emptyList()
        val refSign = extractJsonValue(paramsRaw, "ref_sign") ?: return emptyList()
        val form = mapOf(
            "d" to (extractJsonValue(paramsRaw, "d") ?: domain),
            "d_sign" to dSign,
            "pd" to (extractJsonValue(paramsRaw, "pd") ?: domain),
            "pd_sign" to (extractJsonValue(paramsRaw, "pd_sign") ?: dSign),
            "ref" to (extractJsonValue(paramsRaw, "ref") ?: ""),
            "ref_sign" to refSign,
            "bad_user" to "",
            "cdn_is_working" to "",
            "type" to type,
            "hash" to hash,
            "id" to id
        )

        val response = app.post(
            "https://$domain/ftor",
            data = form,
            headers = API_HEADERS + mapOf(
                "Origin" to "https://$domain",
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val parsed = tryParseJson<KodikResponse>(response) ?: return emptyList()
        val result = mutableListOf<Pair<Int, String>>()

        parsed.link?.takeIf { it.isNotBlank() }?.let {
            result.add((parsed.default ?: 0) to normalizeUrl(it))
        }
        for ((qualityKey, videos) in parsed.links.orEmpty()) {
            val quality = qualityKey.toIntOrNull() ?: 0
            for (video in videos) {
                val src = video.src ?: continue
                val decoded = if (src.startsWith("//")) src else (decodeKodikSrc(src) ?: continue)
                result.add(quality to normalizeUrl(decoded))
            }
        }

        return result.distinctBy { it.second }.sortedByDescending { it.first }
    }

    private fun normalizeUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> "https://$url"
    }

    private fun decodeKodikSrc(src: String): String? {
        val shifted = buildString {
            for (ch in src) {
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    val base = if (ch in 'A'..'Z') 90 else 122
                    var code = ch.code + 18
                    if (code > base) code -= 26
                    append(code.toChar())
                } else {
                    append(ch)
                }
            }
        }
        val padded = when (shifted.length % 4) {
            2 -> "$shifted=="
            3 -> "$shifted="
            else -> shifted
        }
        return try {
            base64Decode(padded)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonValue(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun extractJsVar(html: String, name: String): String? =
        Regex("var\\s+$name\\s*=\\s*[\"']([^\"']*)[\"']")
            .find(html)?.groupValues?.get(1)

    private fun extractVInfo(html: String, key: String): String? =
        Regex("vInfo\\.$key\\s*=\\s*'([^']*)'").find(html)?.groupValues?.get(1)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KodikResponse(
        val links: Map<String, List<KodikVideo>>? = null,
        val link: String? = null,
        val default: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KodikVideo(
        val src: String? = null,
        val type: String? = null
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "user-agent" to BROWSER_UA,
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "accept-language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        private val API_HEADERS = mapOf(
            "user-agent" to BROWSER_UA,
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "ru-RU,ru;q=0.9,en;q=0.8"
        )

        // в HTML: {\"anime\":{\"alloha_iframe\":...
        private const val ANIME_MARKER = "\\\"anime\\\":{\\\"alloha_iframe\\\""
    }
}
