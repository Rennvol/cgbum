package eu.kanade.tachiyomi.extension.id.cgbum

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Source
abstract class Cgbum : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder().rateLimit(2).build()

    // ---------- selectors ----------
    // daftar-komik cards: article.comic-card
    // chapter list: .chapter-grid a.ch-grid-item
    // reader images: #readerImages img[data-url]
    // cover: og:image (img.cgbum.com)
    // title/status inline parsed

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // Popular = daftar-komik?sort=popular — fallback page param
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/daftar-komik?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseComicCards(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/last-update?type=normal&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseComicCards(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        return if (q.isNotEmpty()) {
            GET("$baseUrl/cari/$q?page=$page", headers)
        } else GET("$baseUrl/daftar-komik?page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.code == 404) return MangasPage(emptyList(), false)
        return parseComicCards(response)
    }

    private fun parseComicCards(response: Response): MangasPage {
        val doc = response.asJsoup()
        // cgbum uses article.comic-card with <a href="/komik/slug..."> + <h3>
        var cards = doc.select("article.comic-card")
        if (cards.isEmpty()) cards = doc.select("a[href*=/komik/]")
        // dedupe by href and pick cards that have image or title
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()
        for (el in doc.select("article.comic-card")) {
            val a = el.selectFirst("a[href*=komik]") ?: continue
            val href = a.absUrl("href")
            if (!seen.add(href)) continue
            val titleEl = el.selectFirst("h3, .comic-card-title a, .comic-card-title")
            val title = titleEl?.text()?.trim().takeIf { !it.isNullOrEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: href.substringAfterLast("/").removeSuffix("cgbum").trim('-','_').takeIf { it.isNotEmpty() } ?: continue
            if (title.length < 2) continue
            mangas += SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = el.selectFirst("img")?.let { it.absUrl("src").ifEmpty { it.absUrl("data-src") } }?.takeIf { it.isNotEmpty() }
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            }
        }
        // fallback: direct links if no article parsed
        if (mangas.isEmpty()) {
            for (a in doc.select("a[href*=/komik/]")) {
                val href = a.absUrl("href")
                if (!seen.add(href)) continue
                val t = a.text().trim()
                if (t.length < 2) continue
                mangas += SManga.create().apply {
                    title = t
                    setUrlWithoutDomain(href)
                }
                if (mangas.size >= 30) break
            }
        }
        val hasNext = doc.selectFirst("a[rel=next], .pagination a:contains(Next), .pagination a:contains(Berikutnya)") != null ||
            mangas.size >= 20
        return MangasPage(mangas.distinctBy { it.url }, hasNext)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Bahasa")?.trim()
                ?: ""
            // cover
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[src*=covers], img[src*=cover]")?.absUrl("src")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst(".synopsis, [class*=sinopsis] p")?.text()
            // status: text Ongoing/Completed near badges
            val statusText = doc.selectFirst(".comic-card-badges, .status, [class*=status]")?.text()
                ?: doc.body().text()
            status = when {
                statusText.contains("Ongoing", true) || statusText.contains("On Going", true) -> SManga.ONGOING
                statusText.contains("Completed", true) || statusText.contains("Tamat", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = doc.select("a[href*=genre]").joinToString { it.text().trim() }.takeIf { it.isNotEmpty() }
            author = doc.selectFirst("a[href*=author]")?.text()?.trim()
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val base = response.request.url.toString()
        // primary
        var items = doc.select(".chapter-grid a.ch-grid-item, a.ch-grid-item")
        if (items.isEmpty()) items = doc.select("a[href*=chapter]")
        return items.mapNotNull { a ->
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            if (!href.contains("chapter")) return@mapNotNull null
            val name = a.text().trim().ifEmpty { a.attr("title").trim() }.ifEmpty { "Ch. ${a.attr("data-chapter")}" }
            if (name.isEmpty()) return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                // keep absolute or relative
                setUrlWithoutDomain(href)
                // date: try nearby text "X lalu" else leave 0
                val dateText = a.parent()?.text() ?: a.attr("title")
                date_upload = parseCgbumDate(dateText)
                // chapter_number
                chapter_number = a.attr("data-chapter").toFloatOrNull() ?: Regex("""chapter/(\d+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.distinctBy { it.url }.reversed().ifEmpty {
            // fallback: if no chapter grid found, but page is valid, try base url check
            emptyList()
        }
    }

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
    private fun parseCgbumDate(s: String): Long {
        val t = s.lowercase(Locale.ROOT)
        val cal = Calendar.getInstance()
        return when {
            "menit lalu" in t -> { val n = Regex("""(\d+)\s*menit""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0; cal.add(Calendar.MINUTE, -n); cal.timeInMillis }
            "jam lalu" in t -> { val n = Regex("""(\d+)\s*jam""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0; cal.add(Calendar.HOUR_OF_DAY, -n); cal.timeInMillis }
            "hari lalu" in t -> { val n = Regex("""(\d+)\s*hari""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0; cal.add(Calendar.DAY_OF_YEAR, -n); cal.timeInMillis }
            "minggu lalu" in t -> { val n = Regex("""(\d+)\s*minggu""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0; cal.add(Calendar.WEEK_OF_YEAR, -n); cal.timeInMillis }
            else -> dateFmt.tryParse(s) ?: 0L
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val url = response.request.url.toString()
        // cgbum reader: #readerImages img[data-url] (cdn8.cgbum.com)
        var imgs = doc.select("#readerImages img[data-url]")
        if (imgs.isEmpty()) imgs = doc.select("#readerImages img[src]")
        if (imgs.isEmpty()) imgs = doc.select("img[data-url]")
        if (imgs.isEmpty()) imgs = doc.select(".reader-images img")
        return imgs.mapIndexedNotNull { i, img ->
            val raw = img.attr("data-url").ifEmpty { img.attr("data-src") }.ifEmpty { img.absUrl("src") }.ifEmpty { img.attr("src") }
            if (raw.isEmpty()) return@mapNotNull null
            val abs = if (raw.startsWith("http")) raw else doc.baseUri().let { raw }
            Page(i, url, abs)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val h = headersBuilder().add("Referer", "$baseUrl/").build()
        return GET(page.imageUrl!!, h)
    }
}
