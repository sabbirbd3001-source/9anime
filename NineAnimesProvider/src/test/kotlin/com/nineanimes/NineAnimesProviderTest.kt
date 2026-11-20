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
    fun testTypeDetection() {
        assertEquals(TvType.AnimeMovie, NineAnimesProvider.getType("Movie"))
        assertEquals(TvType.AnimeMovie, NineAnimesProvider.getType("Anime Movie (Sub)"))
        assertEquals(TvType.OVA, NineAnimesProvider.getType("OVA"))
        assertEquals(TvType.OVA, NineAnimesProvider.getType("Special Episode"))
        assertEquals(TvType.Anime, NineAnimesProvider.getType("TV Series"))
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
        assertEquals("Jujutsu Kaisen", NineAnimesProvider.cleanTitle("Jujutsu Kaisen"))
    }

    @Test
    fun testParseCardsHtml() {
        val sampleHtml = """
            <html>
            <body>
                <div class="cards-grid">
                    <a href="/watch/one-piece/" title="One Piece">
                        <img data-src="https://img.9animes.cam/posters/one-piece.jpg" alt="One Piece" />
                        <span class="anime-name">One Piece</span>
                        <div class="tick-sub">1080</div>
                        <div class="tick-dub">1050</div>
                    </a>
                    <a href="/watch/naruto-shippuden/" title="Naruto Shippuden">
                        <img data-original="https://img.9animes.cam/posters/naruto.jpg" alt="Naruto" />
                        <span class="anime-name">Naruto Shippuden</span>
                        <div class="tick-sub">500</div>
                    </a>
                    <a href="#" title="Dummy Banner">
                        <span class="anime-name">Should Be Ignored</span>
                    </a>
                    <a href="javascript:void(0)" title="JS Void">
                        <span class="anime-name">Should Be Ignored Too</span>
                    </a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml, "https://9animes.cam")
        val cards = doc.select("div.cards-grid > a")
        assertEquals(4, cards.size)

        // Test valid card attributes
        val validCards = cards.filter {
            val href = it.attr("href").trim()
            href.isNotBlank() && href != "#" && !href.startsWith("javascript:")
        }
        assertEquals(2, validCards.size)

        val first = validCards[0]
        assertEquals("One Piece", first.attr("title"))
        assertEquals("/watch/one-piece/", first.attr("href"))
        assertEquals("https://img.9animes.cam/posters/one-piece.jpg", first.selectFirst("img")?.attr("data-src"))

        val second = validCards[1]
        assertEquals("https://img.9animes.cam/posters/naruto.jpg", second.selectFirst("img")?.attr("data-original"))
    }

    @Test
    fun testParseDetailPageHtmlWithTrailer() {
        val sampleDetailHtml = """
            <html>
            <head>
                <meta property="og:title" content="Watch One Piece English Subbed Online Free on 9Anime" />
                <meta property="og:image" content="https://img.9animes.cam/cover/one-piece.jpg" />
                <meta name="description" content="Gol D. Roger was known as the Pirate King..." />
            </head>
            <body>
                <div class="film-info">
                    <h1 class="film-name">One Piece</h1>
                    <div class="meta-data">
                        <span>Format: TV Series</span>
                        <span>Premiered: 1999</span>
                        <span>Status: Ongoing</span>
                    </div>
                    <div class="genres">
                        <a href="/genre/action/">Action</a>
                        <a href="/genre/adventure/">Adventure</a>
                        <a href="/genre/fantasy/">Fantasy</a>
                    </div>
                    <p class="description">Gol D. Roger was known as the Pirate King...</p>
                    <iframe src="https://www.youtube.com/embed/sampletrailer123"></iframe>
                </div>
                <div class="episodes-list">
                    <a href="/watch/one-piece/ep-1">Episode 1 - I'm Luffy! The Man Who Will Become the Pirate King!</a>
                    <a href="/watch/one-piece/ep-2">Episode 2 - The Great Swordsman Appears! Pirate Hunter Roronoa Zoro</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleDetailHtml, "https://9animes.cam")

        val rawTitle = doc.selectFirst("h1, .film-name")?.text() ?: ""
        val title = NineAnimesProvider.cleanTitle(rawTitle)
        assertEquals("One Piece", title)

        val formatText = doc.select(".meta-data").text()
        val type = NineAnimesProvider.getType(formatText)
        assertEquals(TvType.Anime, type)

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(formatText)?.value?.toIntOrNull()
        assertEquals(1999, year)

        val genres = doc.select(".genres a").map { it.text() }
        assertEquals(listOf("Action", "Adventure", "Fantasy"), genres)

        val trailer = doc.selectFirst("iframe[src*='youtube.com']")?.attr("src")
        assertEquals("https://www.youtube.com/embed/sampletrailer123", trailer)

        val episodes = doc.select(".episodes-list a")
        assertEquals(2, episodes.size)
        assertEquals("/watch/one-piece/ep-1", episodes[0].attr("href"))
    }

    @Test
    fun testParseDetailPageWithJavaScriptEpisodes() {
        val sampleJsEpHtml = """
            <html>
            <body>
                <div class="film-info">
                    <h1 class="film-name">Solo Leveling</h1>
                    <div class="meta-data">
                        <span>Status: Completed</span>
                        <span>Released: 2024</span>
                    </div>
                </div>
                <div class="episodes-list">
                    <a class="ep-item" href="javascript:void(0)" data-id="99101" data-number="1" title="Episode 1">1</a>
                    <a class="ep-item" href="javascript:void(0)" data-id="99102" data-number="2" title="Episode 2">2</a>
                    <a class="ep-item" href="#" data-id="99103" data-number="3" title="Episode 3">3</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleJsEpHtml, "https://9animes.cam")
        val epElements = doc.select(".ep-item")
        assertEquals(3, epElements.size)

        val baseUrl = "https://9animes.cam/watch/solo-leveling"
        val constructedUrls = epElements.mapIndexed { index, elem ->
            val hrefAttr = elem.attr("href").trim()
            val dataId = elem.attr("data-id")
            val dataNumber = elem.attr("data-number")

            when {
                hrefAttr.isNotBlank() && !hrefAttr.startsWith("#") && !hrefAttr.startsWith("javascript:") -> hrefAttr
                dataId.isNotBlank() -> "$baseUrl?ep=$dataId"
                else -> "$baseUrl?ep=${index + 1}"
            }
        }

        assertEquals("https://9animes.cam/watch/solo-leveling?ep=99101", constructedUrls[0])
        assertEquals("https://9animes.cam/watch/solo-leveling?ep=99102", constructedUrls[1])
        assertEquals("https://9animes.cam/watch/solo-leveling?ep=99103", constructedUrls[2])
    }

    @Test
    fun testParsePlayerEmbedsHtml() {
        val samplePlayerHtml = """
            <html>
            <body>
                <div id="player">
                    <iframe id="main-embed" src="https://streamtape.com/e/6289vXyZ/"></iframe>
                </div>
                <div class="servers">
                    <button class="server-item" data-src="https://filemoon.sx/e/xyz123">Filemoon</button>
                    <button class="server-item" data-src="https://www.mp4upload.com/embed-abc.html">Mp4Upload</button>
                </div>
                <!-- Lazy loaded thumbnails should be ignored by server sources -->
                <div class="recommended">
                    <img data-src="https://img.9animes.cam/posters/recom1.jpg" alt="Poster 1" />
                    <img data-src="https://img.9animes.cam/posters/recom2.png" alt="Poster 2" />
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

        // 1. Iframe verification
        val mainIframe = doc.selectFirst("iframe#main-embed")?.attr("src")
        assertEquals("https://streamtape.com/e/6289vXyZ/", mainIframe)

        // 2. Server button extraction excludes images
        val serverSources = doc.select(".server-item[data-src], button[data-src]").mapNotNull {
            val src = it.attr("data-src")
            if (src.endsWith(".jpg") || src.endsWith(".png")) null else src
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
