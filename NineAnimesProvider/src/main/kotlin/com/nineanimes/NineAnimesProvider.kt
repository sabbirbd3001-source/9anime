package com.nineanimes

import com.lagradost.cloudstream3.*
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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
                .replace(Regex("""(?i)\s*-\s*Anime\s+Episodes\s+Online.*"""), "")
                .trim()
        }
    }

    private val savedCookies = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var cachedWebViewUserAgent: String? = null

    private fun getAndroidCookie(url: String): String? {
        return try {
            val cmClass = Class.forName("android.webkit.CookieManager")
            val getInstanceMethod = cmClass.getMethod("getInstance")
            val cookieManager = getInstanceMethod.invoke(null) ?: return null
            val getCookieMethod = cmClass.getMethod("getCookie", String::class.java)
            getCookieMethod.invoke(cookieManager, url) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCookieMap(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    private fun getHost(url: String): String {
        return try {
            URI(url).host ?: "9animes.cam"
        } catch (_: Throwable) {
            "9animes.cam"
        }
    }

    private fun getHeaders(url: String): Map<String, String> {
        val ua = cachedWebViewUserAgent
            ?: WebViewResolver.getWebViewUserAgent()
            ?: USER_AGENT
        return mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }

    private fun trySolveWithSavedCookies(url: String, host: String): Boolean {
        return try {
            val cookie = getAndroidCookie(url)
            if (cookie != null && (cookie.contains("cf_clearance") || cookie.contains("__cf_bm") || cookie.contains("bcg"))) {
                savedCookies[host] = parseCookieMap(cookie)
                cachedWebViewUserAgent = WebViewResolver.getWebViewUserAgent()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun resolveCloudflare(url: String) {
        val host = getHost(url)
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(".^"), // Never exit based on url
                additionalUrls = listOf(Regex(".")),
                userAgent = null, // Cloudflare needs default native WebView user agent
                useOkhttp = false // Let webview handle requests so cookies are set
            )

            resolver.resolveUsingWebView(url) {
                trySolveWithSavedCookies(url, host)
            }
            cachedWebViewUserAgent = WebViewResolver.getWebViewUserAgent()
        } catch (_: Throwable) {
            // Resolver may fail in non-Android environments, ignore
        }
    }

    private suspend fun fetchDocument(url: String): Document {
        val host = getHost(url)
        val cookies = savedCookies[host] ?: emptyMap()

        // 1. First attempt with any cached cookies
        try {
            val res = app.get(
                url = url,
                headers = getHeaders(url),
                cookies = cookies
            )
            val doc = res.document
            val title = doc.title()
            val isChallenge = title.contains("Just a moment", ignoreCase = true) ||
                    title.contains("Verify you are human", ignoreCase = true) ||
                    doc.selectFirst("div#challenge-running, div#turnstile-wrapper, div.cf-turnstile") != null

            if (res.code in 200..299 && !isChallenge) {
                return doc
            }
        } catch (_: Throwable) {
            // Fall through to challenge resolution
        }

        // 2. Solve challenge via WebView
        resolveCloudflare(url)

        // 3. Retry with freshly resolved cookies
        val freshCookies = savedCookies[host] ?: emptyMap()
        val retryRes = app.get(
            url = url,
            headers = getHeaders(url),
            cookies = freshCookies
        )
        return retryRes.document
    }

    override val mainPage = mainPageOf(
        "$mainUrl/popular/" to "Popular Anime",
        "$mainUrl/latest-episodes/" to "Latest Episodes",
        "$mainUrl/new/" to "Recently Added",
        "$mainUrl/movie/" to "Anime Movies",
        "$mainUrl/top-airing/" to "Top Airing",
        "$mainUrl/coming-soon/" to "Coming Soon"
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
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/filter/?keyword=$encoded",
            "$mainUrl/popular/?keyword=$encoded",
            "$mainUrl/?s=$encoded"
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

        val rawTitle = doc.selectFirst("h1.detail-title, h1.dynamic-name, h1, .film-name, .entry-title, h2.title, .heading-large")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".detail-poster img, img.poster-img, img.cover, img.poster, .film-poster img, .poster img, .anime-poster img")?.let { img ->
            val dataSrc = img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("data-lazy-src") }
            if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) {
                dataSrc
            } else {
                val src = img.attr("src")
                if (src.isNotBlank() && !src.startsWith("data:")) src else null
            }
        }?.let { fixUrlNull(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }

        val plot = doc.selectFirst(".detail-description, .synopsis, .description, .film-description, .entry-content, p.desc, .text")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        var status: ShowStatus = ShowStatus.Ongoing
        var type: TvType = TvType.Anime
        var year: Int? = null
        val genres = mutableListOf<String>()

        doc.select(".meta-row").forEach { row ->
            val label = row.selectFirst(".meta-label")?.text()?.lowercase() ?: ""
            val valueElem = row.selectFirst(".meta-value")
            val valueText = valueElem?.text()?.trim() ?: ""

            when {
                label.contains("status") -> status = getStatus(valueText)
                label.contains("type") -> type = getType(valueText)
                label.contains("premiered") || label.contains("date") -> {
                    val y = Regex("""\b(19\d\d|20\d\d)\b""").find(valueText)?.value?.toIntOrNull()
                    if (year == null && y != null) year = y
                }
                label.contains("genre") -> {
                    val genreLinks = valueElem?.select("a")
                    if (!genreLinks.isNullOrEmpty()) {
                        genreLinks.forEach { a ->
                            val g = a.text().trim()
                            if (g.isNotBlank()) genres.add(g)
                        }
                    } else if (valueText.isNotBlank()) {
                        valueText.split(",").forEach { g ->
                            val trimmed = g.trim()
                            if (trimmed.isNotBlank()) genres.add(trimmed)
                        }
                    }
                }
            }
        }

        // Fallbacks for genres and year
        if (genres.isEmpty()) {
            genres.addAll(
                doc.select(".genres a, .meta-data a, a[href*='/genre/'], .tags a, .meta a[href*='/genre/']")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
            )
        }
        if (year == null) {
            val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(
                doc.select(".meta-data, .anime-info, a[href*='/year/'], .film-info").text()
            )?.value
            year = yearMatch?.toIntOrNull()
        }

        // Parse trailer if present
        val trailerUrl = doc.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")?.attr("src")
            ?.let { fixUrlNull(it) }

        // Parse episode elements
        val episodes = mutableListOf<Episode>()
        val directEpisodeElements = doc.select(
            ".ep-item, a.ep-item, ul.ep-grid li a, .episodes-list a, #episodes a, ul.episodes li a, .servers-list a, div.server a, a.episode-btn, .range a, .ss-list a, ul.ep-grid li"
        )

        if (directEpisodeElements.isNotEmpty()) {
            directEpisodeElements.forEachIndexed { index, elem ->
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
            // Check for season pills
            val seasonPills = doc.select(".season-list a.season-pill, a.season-pill")
            if (seasonPills.isNotEmpty()) {
                seasonPills.forEachIndexed { sIndex, sElem ->
                    val sHref = fixUrl(sElem.attr("href"))
                    val sName = sElem.text().trim()
                    val sNum = Regex("""\d+""").find(sName)?.value?.toIntOrNull() ?: (sIndex + 1)

                    episodes.add(
                        newEpisode(sHref) {
                            this.name = sName.ifBlank { "Season $sNum" }
                            this.episode = sNum
                            this.season = sNum
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
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres.distinct()
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
        val iframeUrls = doc.select(
            "iframe#main-embed, iframe[src*='stream'], iframe[src*='embed'], div#player iframe, " +
            "iframe.lozad, div.player-wrapper iframe, .player iframe, #iframe-embed, iframe"
        )
            .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") } }
            .filter {
                it.isNotBlank() && !it.startsWith("javascript:") && it != "#" &&
                !it.contains("youtube.com") && !it.contains("youtu.be") &&
                !it.contains("google") && !it.contains("analytics")
            }
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
                !it.endsWith(".png", true) && !it.endsWith(".jpg", true) && !it.endsWith(".jpeg", true) &&
                !it.endsWith(".webp", true) && !it.endsWith(".gif", true) && !it.endsWith(".svg", true)
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
        val fileMatches = Regex("""(?:file|source|src)\s*:\s*["'](https?://[^"']+)["']""").findAll(scriptContent)
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

    internal fun parseCards(doc: Document): List<SearchResponse> {
        val cardElements = doc.select(
            "div.anime-card, .anime-card, div.cards-grid .card, div.cards-grid > a, " +
            "div.flw-item, div.film-item, article.anime, a.top10-item, a.top10-item-featured"
        )

        return cardElements.mapNotNull { elem ->
            val linkElem = elem.selectFirst("a.anime-card-link, a[href*='/watch/'], a") ?: elem
            val rawHref = linkElem.attr("href").ifBlank {
                linkElem.attr("data-href").ifBlank {
                    elem.attr("data-qtip-link").ifBlank {
                        elem.selectFirst("a")?.attr("href") ?: ""
                    }
                }
            }

            if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("javascript:")) return@mapNotNull null
            val href = fixUrl(rawHref)

            val rawTitle = elem.selectFirst(".anime-card-title, h2.anime-name, .anime-name, h2, h3, .dynamic-name, .top10-item-title, .top10-featured-title, .film-name a, .film-name")?.text()?.trim()
                ?.ifBlank { null }
                ?: linkElem.attr("title").trim().ifBlank { null }
                ?: elem.selectFirst("h2")?.attr("data-title-en")?.trim()?.ifBlank { null }
                ?: elem.attr("title").trim().ifBlank { null }
                ?: elem.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }

            if (rawTitle.isNullOrBlank()) return@mapNotNull null
            val title = cleanTitle(rawTitle)

            val posterElem = elem.selectFirst("img")
            val posterUrl = posterElem?.let { img ->
                val dataSrc = img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("data-lazy-src") }
                if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) {
                    dataSrc
                } else {
                    val src = img.attr("src")
                    if (src.isNotBlank() && !src.startsWith("data:")) src else null
                }
            }?.let { fixUrlNull(it) }

            val epText = elem.selectFirst(".badge-episodes, .tick-eps, .ep-status, .episode-number")?.text()?.trim()
            val epCount = Regex("""\d+""").find(epText ?: "")?.value?.toIntOrNull()

            val typeText = elem.selectFirst(".anime-card-meta span:not(.anime-card-rating), .top10-type")?.text()?.trim()
            val type = getType(typeText)

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                addSub(epCount)
            }
        }.distinctBy { it.url }
    }
}
