package com.nineanimes

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NineAnimesProviderTest {

    private lateinit var provider: NineAnimesProvider

    @Before
    fun setUp() {
        provider = NineAnimesProvider()
    }

    @Test
    fun testProviderProperties() {
        assertEquals("9Anime", provider.name)
        assertEquals("https://9animes.cam", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertEquals("en", provider.lang)
        assertTrue(provider.supportedTypes.contains(TvType.Anime))
        assertTrue(provider.supportedTypes.contains(TvType.AnimeMovie))
        assertTrue(provider.supportedTypes.contains(TvType.OVA))
    }

    @Test
    fun testMainPageSections() {
        val sections = provider.mainPage
        assertEquals(6, sections.size)
        assertEquals("https://9animes.cam/popular/", sections[0].data)
        assertEquals("Popular Anime", sections[0].name)
        assertEquals("https://9animes.cam/latest-episodes/", sections[1].data)
        assertEquals("Latest Episodes", sections[1].name)
        assertEquals("https://9animes.cam/new/", sections[2].data)
        assertEquals("Recently Added", sections[2].name)
        assertEquals("https://9animes.cam/movie/", sections[3].data)
        assertEquals("Anime Movies", sections[3].name)
        assertEquals("https://9animes.cam/top-airing/", sections[4].data)
        assertEquals("Top Airing", sections[4].name)
        assertEquals("https://9animes.cam/coming-soon/", sections[5].data)
        assertEquals("Coming Soon", sections[5].name)
    }

    @Test
    fun testTypeDetection() {
        assertEquals(TvType.AnimeMovie, NineAnimesProvider.getType("Movie"))
        assertEquals(TvType.AnimeMovie, NineAnimesProvider.getType("Anime Movie (Sub)"))
        assertEquals(TvType.OVA, NineAnimesProvider.getType("OVA"))
        assertEquals(TvType.OVA, NineAnimesProvider.getType("Special Episode"))
        assertEquals(TvType.Anime, NineAnimesProvider.getType("TV Series"))
        assertEquals(TvType.Anime, NineAnimesProvider.getType("TV"))
        assertEquals(TvType.Anime, NineAnimesProvider.getType("ONA"))
        assertEquals(TvType.Anime, NineAnimesProvider.getType(null))
    }

    @Test
    fun testStatusDetection() {
        assertEquals(ShowStatus.Completed, NineAnimesProvider.getStatus("Completed"))
        assertEquals(ShowStatus.Completed, NineAnimesProvider.getStatus("Finished Airing"))
        assertEquals(ShowStatus.Ongoing, NineAnimesProvider.getStatus("Ongoing"))
        assertEquals(ShowStatus.Ongoing, NineAnimesProvider.getStatus("Currently Airing"))
        assertEquals(ShowStatus.Ongoing, NineAnimesProvider.getStatus("Upcoming Season"))
        assertEquals(ShowStatus.Ongoing, NineAnimesProvider.getStatus(null))
    }

    @Test
    fun testCleanTitle() {
        assertEquals("One Piece", NineAnimesProvider.cleanTitle("Watch One Piece English Subbed Online Free on 9Anime"))
        assertEquals("Naruto Shippuden", NineAnimesProvider.cleanTitle("Naruto Shippuden - 9Anime"))
        assertEquals("Bleach", NineAnimesProvider.cleanTitle("Bleach | 9Anime"))
        assertEquals("Attack on Titan", NineAnimesProvider.cleanTitle("Watch Attack on Titan Dub Online"))
        assertEquals("Black Clover", NineAnimesProvider.cleanTitle("Black Clover - Anime Episodes Online | 9Anime"))
        assertEquals("Jujutsu Kaisen", NineAnimesProvider.cleanTitle("Jujutsu Kaisen"))
    }

    @Test
    fun testParseRealAnimeCardHtml() {
        val sampleHtml = """
            <html>
            <body>
                <div class="content-section">
                    <div class="anime-card" data-qtip-id="2602" data-qtip-link="https://9animes.cam/watch/black-clover/">
                        <a class="anime-card-link" href="https://9animes.cam/watch/black-clover/" title="Black Clover">
                            <div class="anime-card-image">
                                <img alt="Black Clover" class="lazy" data-src="https://i1.wp.com/image.tmdb.org/t/p/w500/uZUtoVChBGhNj9OfrM2LYpwBm8E.jpg" src="data:image/svg+xml;base64,PHN2ZyB2aWV3Qm94PSIwIDAgMjMwIDM0NSI+PC9zdmc+" width="230" height="345"/>
                                <span class="anime-card-badge badge-year">2026</span>
                                <span class="anime-card-badge badge-episodes">Ep 196</span>
                            </div>
                            <div class="anime-card-body">
                                <h2 class="anime-card-title anime-name" data-title-en="Black Clover" data-title-jp="">Black Clover</h2>
                                <div class="anime-card-meta">
                                    <span>TV</span>
                                    <span class="anime-card-rating">8.9</span>
                                </div>
                            </div>
                        </a>
                    </div>
                    <div class="anime-card" data-qtip-id="53" data-qtip-link="https://9animes.cam/watch/one-piece/">
                        <a class="anime-card-link" href="https://9animes.cam/watch/one-piece/" title="One Piece">
                            <div class="anime-card-image">
                                <img alt="One Piece" class="lazy" data-src="https://i1.wp.com/image.tmdb.org/t/p/w500/dB4EDhre2dsC2kxYDavyKWqLQwi.jpg" src="data:image/svg+xml;base64,placeholder" width="230" height="345"/>
                                <span class="anime-card-badge badge-episodes">Ep 1180</span>
                            </div>
                            <div class="anime-card-body">
                                <h2 class="anime-card-title anime-name">One Piece</h2>
                                <div class="anime-card-meta">
                                    <span>TV</span>
                                </div>
                            </div>
                        </a>
                    </div>
                    <!-- Invalid cards should be ignored -->
                    <div class="anime-card">
                        <a class="anime-card-link" href="#" title="Dummy">
                            <h2 class="anime-card-title">Empty Link</h2>
                        </a>
                    </div>
                    <div class="anime-card">
                        <a class="anime-card-link" href="javascript:void(0)" title="JS Void">
                            <h2 class="anime-card-title">JS Link</h2>
                        </a>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml, "https://9animes.cam")
        val parsed = provider.parseCards(doc)

        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals("Black Clover", first.name)
        assertEquals("https://9animes.cam/watch/black-clover/", first.url)
        assertEquals("https://i1.wp.com/image.tmdb.org/t/p/w500/uZUtoVChBGhNj9OfrM2LYpwBm8E.jpg", first.posterUrl)
        assertEquals(TvType.Anime, first.type)

        val second = parsed[1]
        assertEquals("One Piece", second.name)
        assertEquals("https://9animes.cam/watch/one-piece/", second.url)
        assertEquals("https://i1.wp.com/image.tmdb.org/t/p/w500/dB4EDhre2dsC2kxYDavyKWqLQwi.jpg", second.posterUrl)
    }

    @Test
    fun testParseRealDetailPageHtml() {
        val sampleDetailHtml = """
            <html>
            <head>
                <meta property="og:title" content="Black Clover - Anime Episodes Online | 9Anime" />
                <title>Black Clover - Anime Episodes Online | 9Anime</title>
            </head>
            <body>
                <div class="movie-detail show-detail show-detail-v2">
                    <div class="container">
                        <div class="detail-main-content">
                            <div class="detail-card">
                                <div class="detail-poster">
                                    <img class="poster-img" src="https://i1.wp.com/image.tmdb.org/t/p/w500/uZUtoVChBGhNj9OfrM2LYpwBm8E.jpg?ssl=1&resize=230,345" alt="Black Clover" />
                                </div>
                                <div class="detail-info">
                                    <h1 class="detail-title dynamic-name" data-jname="Black Clover">Black Clover</h1>
                                    <div class="detail-alt-titles">ブラッククローバー 2nd season</div>
                                    <div class="detail-description">
                                        Asta and Yuno are two orphans who want the same thing: to become the Wizard King.
                                    </div>
                                    <div class="detail-meta">
                                        <div class="meta-column">
                                            <div class="meta-row">
                                                <span class="meta-label">Type:</span>
                                                <span class="meta-value"><a href="https://9animes.cam/type/tv/">TV</a></span>
                                            </div>
                                            <div class="meta-row">
                                                <span class="meta-label">Date aired:</span>
                                                <span class="meta-value">Oct 3, 2026 to ?</span>
                                            </div>
                                            <div class="meta-row">
                                                <span class="meta-label">Status:</span>
                                                <span class="meta-value">Currently Airing</span>
                                            </div>
                                            <div class="meta-row">
                                                <span class="meta-label">Genre:</span>
                                                <span class="meta-value">
                                                    <a href="https://9animes.cam/genre/action/">Action</a>,
                                                    <a href="https://9animes.cam/genre/fantasy/">Fantasy</a>
                                                </span>
                                            </div>
                                        </div>
                                        <div class="meta-column">
                                            <div class="meta-row">
                                                <span class="meta-label">Premiered:</span>
                                                <span class="meta-value">Fall 2026</span>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <div class="season-section">
                                <div class="season-list">
                                    <a class="season-pill" href="https://9animes.cam/watch/black-clover/season/1/">1. Season 1</a>
                                    <a class="season-pill" href="https://9animes.cam/watch/black-clover/season/2/">2. Season 2</a>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleDetailHtml, "https://9animes.cam")

        val rawTitle = doc.selectFirst("h1.detail-title, h1.dynamic-name")?.text()?.trim() ?: ""
        val title = NineAnimesProvider.cleanTitle(rawTitle)
        assertEquals("Black Clover", title)

        val poster = doc.selectFirst(".detail-poster img")?.attr("src")
        assertEquals("https://i1.wp.com/image.tmdb.org/t/p/w500/uZUtoVChBGhNj9OfrM2LYpwBm8E.jpg?ssl=1&resize=230,345", poster)

        val plot = doc.selectFirst(".detail-description")?.text()?.trim()
        assertEquals("Asta and Yuno are two orphans who want the same thing: to become the Wizard King.", plot)

        val genres = doc.select(".meta-row a[href*='/genre/']").map { it.text().trim() }
        assertEquals(listOf("Action", "Fantasy"), genres)

        val seasons = doc.select(".season-list a.season-pill")
        assertEquals(2, seasons.size)
        assertEquals("https://9animes.cam/watch/black-clover/season/1/", seasons[0].attr("href"))
        assertEquals("https://9animes.cam/watch/black-clover/season/2/", seasons[1].attr("href"))
    }

    @Test
    fun testParseDirectEpisodesListHtml() {
        val sampleEpDoc = """
            <html>
            <body>
                <div class="episodes-list">
                    <a class="ep-item" href="/watch/naruto/ep-1" title="Episode 1">Ep 1</a>
                    <a class="ep-item" href="/watch/naruto/ep-2" title="Episode 2">Ep 2</a>
                    <a class="ep-item" href="/watch/naruto/ep-3" title="Episode 3">Ep 3</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleEpDoc, "https://9animes.cam")
        val episodes = doc.select(".episodes-list a")
        assertEquals(3, episodes.size)
        assertEquals("/watch/naruto/ep-1", episodes[0].attr("href"))
        assertEquals("Episode 1", episodes[0].attr("title"))
    }

    @Test
    fun testParsePlayerEmbedsHtml() {
        val samplePlayerHtml = """
            <html>
            <body>
                <div id="player">
                    <iframe id="main-embed" src="https://streamtape.com/e/6289vXyZ/"></iframe>
                    <!-- Ad and youtube iframes should be filtered -->
                    <iframe src="https://www.google.com/ads"></iframe>
                    <iframe src="https://www.youtube.com/embed/dummy"></iframe>
                </div>
                <div class="servers">
                    <button class="server-item" data-src="https://filemoon.sx/e/xyz123">Filemoon</button>
                    <button class="server-item" data-src="https://www.mp4upload.com/embed-abc.html">Mp4Upload</button>
                    <button class="server-item" data-src="https://img.9animes.cam/ad.png">Image Ad</button>
                </div>
                <video>
                    <source src="https://cdn.example.com/direct/video.mp4" type="video/mp4" />
                </video>
                <script>
                    var player = new Player({ file: "https://stream.example.com/hls/master.m3u8" });
                </script>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(samplePlayerHtml, "https://9animes.cam")

        // 1. Iframe verification (excluding google/youtube)
        val validIframes = doc.select("iframe").mapNotNull { it.attr("src") }.filter {
            it.isNotBlank() && !it.contains("google") && !it.contains("youtube")
        }
        assertEquals(1, validIframes.size)
        assertEquals("https://streamtape.com/e/6289vXyZ/", validIframes[0])

        // 2. Server button extraction excludes images
        val serverSources = doc.select(".server-item[data-src]").mapNotNull {
            val src = it.attr("data-src")
            if (src.endsWith(".png") || src.endsWith(".jpg")) null else src
        }
        assertEquals(2, serverSources.size)
        assertTrue(serverSources.contains("https://filemoon.sx/e/xyz123"))
        assertTrue(serverSources.contains("https://www.mp4upload.com/embed-abc.html"))

        // 3. Direct HTML5 video source
        val directVideo = doc.selectFirst("video source")?.attr("src")
        assertEquals("https://cdn.example.com/direct/video.mp4", directVideo)

        // 4. Script source
        val scriptContent = doc.select("script:not([src])").map { it.data() }.joinToString("\n")
        val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(scriptContent)?.groupValues?.get(1)
        assertEquals("https://stream.example.com/hls/master.m3u8", fileMatch)
    }

    @Test
    fun testSearchBlankQueryReturnsEmpty() = runBlocking {
        val emptyResult = provider.search("")
        assertTrue(emptyResult.isEmpty())

        val whitespaceResult = provider.search("    ")
        assertTrue(whitespaceResult.isEmpty())
    }
}
