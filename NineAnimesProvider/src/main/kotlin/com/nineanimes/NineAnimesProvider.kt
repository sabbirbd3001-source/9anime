package com.nineanimes

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NineAnimesProvider : MainAPI() {

    override var mainUrl = "https://9animes.cam"
    override var name = "9Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun getType(typeString: String?): TvType {
            if (typeString == null) return TvType.Anime
            val lower = typeString.lowercase()
            return when {
                lower.contains("movie") -> TvType.AnimeMovie
                lower.contains("ova") || lower.contains("special") -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(statusString: String?): ShowStatus {
            if (statusString == null) return ShowStatus.Ongoing
            val lower = statusString.lowercase()
            return when {
                lower.contains("completed") || lower.contains("finished") -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
        }

        fun cleanTitle(title: String): String {
            return title
                .replace(Regex("""(?i)^Watch\s+"""), "")
                .replace(Regex("""(?i)\s*(?:English\s+)?(?:Sub(?:bed)?|Dub(?:bed)?)\s+Online.*"""), "")
                .replace(Regex("""(?i)\s*-\s*9Anime.*"""), "")
                .replace(Regex("""(?i)\s*\|\s*9Anime.*"""), "")
                .trim()
        }
    }

    // Cloudflare interceptor using WebView to handle Turnstile / Cloudflare challenges on Android
    private val webViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("""https?://(?:www\.)?9animes\.cam.*"""),
            additionalUrls = listOf(
                Regex(""".*challenges\.cloudflare\.com/.*""")
            ),
            userAgent = USER_AGENT
        )
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/popular/" to "Popular Anime",
        "$mainUrl/movie/" to "Anime Movies",
        "$mainUrl/az-list/" to "A-Z List",
        "$mainUrl/" to "Recently Updated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data.removeSuffix("/")}/?page=$page"
            }
        } else {
            request.data
        }

        val doc = try {
            fetchDocument(url)
        } catch (_: Throwable) {
            return null
        }
        val items = parseCards(doc)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encoded = query.encodeUri()
        val searchUrls = listOf(
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/popular/?keyword=$encoded",
            "$mainUrl/filter?keyword=$encoded"
        )

        for (searchUrl in searchUrls) {
            try {
                val doc = fetchDocument(searchUrl)
                val results = parseCards(doc)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Throwable) {
                // Try next pattern if one fails
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            fetchDocument(url)
        } catch (_: Throwable) {
            return null
        }

        val rawTitle = doc.selectFirst("h1, .film-name, .entry-title, h2.title, .heading-large")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("img.cover, img.poster, .film-poster img, .poster img, .anime-poster img")?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-original") }
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("src") }
        }?.let { fixUrlNull(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }

        val plot = doc.selectFirst(".synopsis, .description, .film-description, .entry-content, p.desc, .text")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val genres = doc.select(".genres a, .meta-data a, a[href*='/genre/'], .tags a, .meta a[href*='/genre/']").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val formatText = doc.select(".meta-data, .anime-info, .film-info, .detail_page-watch, .item-head").text()
        val type = getType(formatText)

        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(
            doc.select(".meta-data, .anime-info, a[href*='/year/'], .film-info").text()
        )?.value
        val year = yearMatch?.toIntOrNull()

        val status = getStatus(formatText)

        // Parse trailer if present
        val trailerUrl = doc.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")?.attr("src")
            ?.let { fixUrlNull(it) }

        // Parse episode elements
        val episodes = mutableListOf<Episode>()
        val episodeElements = doc.select(
            ".ep-item, a.ep-item, ul.ep-grid li a, .episodes-list a, #episodes a, ul.episodes li a, .servers-list a, div.server a, div.ep-item a, a.episode-btn, .range a, .ss-list a, ul.ep-grid li"
        )

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, elem ->
                val linkElem = if (elem.tagName().equals("a", ignoreCase = true)) elem else (elem.selectFirst("a") ?: elem)
                val hrefAttr = linkElem.attr("href").trim()
                val dataId = linkElem.attr("data-id").ifBlank { elem.attr("data-id") }
                val dataNumber = linkElem.attr("data-number").ifBlank { elem.attr("data-number") }
                val rawEpTitle = linkElem.attr("title").ifBlank {
                    linkElem.text().trim()
                }

                val epNumber = dataNumber.toIntOrNull()
                    ?: Regex("""\b(?:Ep(?:isode)?\.?\s*|#)?(\d+)\b""", RegexOption.IGNORE_CASE).find(rawEpTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)

                val epName = rawEpTitle.ifBlank { "Episode $epNumber" }

                // Safe URL construction avoiding javascript:void(0) or #
                val epUrl = when {
                    hrefAttr.isNotBlank() && !hrefAttr.startsWith("#") && !hrefAttr.startsWith("javascript:") -> {
                        fixUrl(hrefAttr)
                    }
                    dataId.isNotBlank() -> {
                        if (url.contains("?")) "$url&ep=$dataId" else "$url?ep=$dataId"
                    }
                    else -> {
                        if (url.contains("?")) "$url&ep=$epNumber" else "$url?ep=$epNumber"
                    }
                }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNumber
                    }
                )
            }
        } else {
            // Self-contained single movie or direct player page
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
            if (!trailerUrl.isNullOrBlank()) {
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        // 1. If data itself is a direct stream URL
        if (data.contains(".m3u8", ignoreCase = true)) {
            try {
                M3u8Helper.generateM3u8(name, data, "$mainUrl/").forEach {
                    callback.invoke(it)
                    foundAny = true
                }
            } catch (_: Throwable) {}
            return foundAny
        }

        if (data.contains(".mp4", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        // 2. Fetch episode watch page
        val doc = try {
            fetchDocument(data)
        } catch (_: Throwable) {
            return false
        }

        // 3. Extract player iframes
        val iframeUrls = doc.select("iframe#main-embed, iframe[src*='stream'], iframe[src*='embed'], div#player iframe, iframe.lozad, div.player-wrapper iframe, .player iframe, #iframe-embed")
            .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") } }
            .filter { it.isNotBlank() && !it.startsWith("javascript:") && it != "#" }
            .map { fixUrl(it) }
            .distinct()

        for (iframe in iframeUrls) {
            try {
                val loaded = loadExtractor(iframe, data, subtitleCallback, callback)
                if (loaded) foundAny = true
            } catch (_: Throwable) {}
        }

        // 4. Extract targeted server buttons/embeds (strictly excluding images and scripts)
        val serverSources = doc.select(
            ".server[data-src], .servers [data-src], .server-item[data-src], button[data-src], .play-btn[data-src], " +
            "li[data-video], button[data-video], .server[data-video], [data-embed]"
        )
            .mapNotNull {
                val src = it.attr("data-src")
                    .ifBlank { it.attr("data-video") }
                    .ifBlank { it.attr("data-embed") }
                if (src.isNotBlank() && !src.startsWith("javascript:") && src != "#") fixUrl(src) else null
            }
            .filter {
                !it.endsWith(".png", true) && !it.endsWith(".jpg", true) && !it.endsWith(".jpeg", true) && !it.endsWith(".webp", true) && !it.endsWith(".gif", true)
            }
            .distinct()

        for (src in serverSources) {
            try {
                val loaded = loadExtractor(src, data, subtitleCallback, callback)
                if (loaded) foundAny = true
            } catch (_: Throwable) {}
        }

        // 5. Extract direct HTML5 video / source tags
        doc.select("video source, video").forEach { sourceElem ->
            val src = fixUrlNull(sourceElem.attr("src").ifBlank { sourceElem.attr("data-src") }) ?: return@forEach
            if (src.contains(".m3u8", ignoreCase = true)) {
                try {
                    M3u8Helper.generateM3u8(name, src, "$mainUrl/").forEach { link ->
                        callback.invoke(link)
                        foundAny = true
                    }
                } catch (_: Throwable) {}
            } else if (src.contains(".mp4", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                foundAny = true
            }
        }

        // 6. Extract inline script player sources (e.g. file: "https://...")
        val scriptContent = doc.select("script:not([src])").map { it.data() }.joinToString("\n")
        val fileMatches = Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(scriptContent)
        for (match in fileMatches) {
            val fileUrl = match.groupValues[1]
            if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                try {
                    M3u8Helper.generateM3u8(name, fileUrl, "$mainUrl/").forEach { link ->
                        callback.invoke(link)
                        foundAny = true
                    }
                } catch (_: Throwable) {}
            } else if (fileUrl.contains(".mp4", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fileUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                foundAny = true
            } else {
                try {
                    val loaded = loadExtractor(fileUrl, data, subtitleCallback, callback)
                    if (loaded) foundAny = true
                } catch (_: Throwable) {}
            }
        }

        return foundAny
    }

    private suspend fun fetchDocument(url: String): Document {
        return try {
            app.get(
                url = url,
                headers = defaultHeaders,
                interceptor = webViewResolver
            ).document
        } catch (_: Throwable) {
            // Fallback to standard request if WebViewResolver fails or on non-Android runtimes
            app.get(
                url = url,
                headers = defaultHeaders
            ).document
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val cardElements = doc.select(
            "div.cards-grid > a, div.cards-grid .card, div.flw-item, div.anime-item, " +
            "div.film_list-wrap .flw-item, div.film_list-wrap div.item, article.anime, " +
            "div.anime-card, div.card, a.anime-meta, .film-item, .animeblock, article.post"
        )

        return cardElements.mapNotNull { elem ->
            val linkElem = when {
                elem.tagName().equals("a", ignoreCase = true) -> elem
                else -> elem.selectFirst(".film-poster a, .film-name a, h3 a, a[href*='/watch/'], a[href*='/anime/'], a") ?: elem
            }

            val rawHref = linkElem.attr("href").ifBlank {
                linkElem.attr("data-href").ifBlank {
                    elem.selectFirst("a")?.attr("href") ?: ""
                }
            }

            if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("javascript:")) return@mapNotNull null
            val href = fixUrl(rawHref)

            val rawTitle = elem.selectFirst(".film-name a, .film-name, h3.title, h3 a, h3, .entry-title, a.title, span.anime-name, .dynamic-name")?.text()?.trim()
                ?.ifBlank { null }
                ?: linkElem.attr("title").trim().ifBlank { null }
                ?: elem.attr("title").trim().ifBlank { null }
                ?: elem.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }

            if (rawTitle.isNullOrBlank()) return@mapNotNull null
            val title = cleanTitle(rawTitle)

            val posterElem = elem.selectFirst("img")
            val posterUrl = posterElem?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("data-original") }
                    .ifBlank { it.attr("data-lazy-src") }
                    .ifBlank { it.attr("src") }
            }?.let { fixUrlNull(it) }

            val subCount = elem.selectFirst(".tick-sub, .fdi-item:contains(Sub)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val dubCount = elem.selectFirst(".tick-dub, .fdi-item:contains(Dub)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val epCount = elem.selectFirst(".tick-eps, .ep-status, .episode-number")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: subCount
                ?: dubCount

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(
                    dubExist = (dubCount != null && dubCount > 0) || elem.selectFirst(".tick-dub") != null,
                    subExist = (subCount != null && subCount > 0) || elem.selectFirst(".tick-sub") != null || (dubCount == null),
                    dubEpisodes = dubCount ?: epCount,
                    subEpisodes = subCount ?: epCount
                )
            }
        }.distinctBy { it.url }
    }
}
