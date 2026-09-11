package eu.kanade.tachiyomi.extension.id.cgbum

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Cgbum : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply { rateLimit(2) }

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val dateFmtJakarta = ZoneId.of("Asia/Jakarta")
    private val chapterRegex = Regex("""(?:Chapter|Ch\.)\s+([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/daftar-komik?page=$page".toHttpUrl()
        return mangaListParse(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/last-update?type=normal&page=$page".toHttpUrl()
        return mangaListParse(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = query.trim()
        val url = if (q.isNotEmpty()) {
            "$baseUrl/cari/$q?page=$page".toHttpUrl()
        } else {
            "$baseUrl/daftar-komik?page=$page".toHttpUrl()
        }
        val doc = client.get(url).asJsoup()
        if (doc.selectFirst("article.comic-card, a[href*=/komik/]") == null && doc.text().contains("tidak ditemukan", true)) {
            return MangasPage(emptyList(), false)
        }
        return mangaListParse(doc)
    }

    private fun mangaListParse(doc: Document): MangasPage {
        val cards = doc.select("article.comic-card")
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()
        if (cards.isNotEmpty()) {
            for (el in cards) {
                val a = el.selectFirst("a[href*=komik]") ?: continue
                val href = a.absUrl("href")
                if (!seen.add(href)) continue
                val title = el.selectFirst("h3, .comic-card-title a, .comic-card-title")?.text()?.trim()
                    ?: a.attr("title").trim().ifEmpty { null }
                    ?: href.substringAfterLast("/").removeSuffix("cgbum").trim('-', '_').ifEmpty { null } ?: continue
                if (title.length < 2) continue
                mangas += SManga.create().apply {
                    this.title = title
                    setUrlWithoutDomain(href)
                    thumbnail_url = el.selectFirst("img")?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }?.takeIf { it.isNotEmpty() }
                }
            }
        }
        if (mangas.isEmpty()) {
            for (a in doc.select("a[href*=/komik/]")) {
                val href = a.absUrl("href")
                if (!seen.add(href)) continue
                val t = a.text().trim()
                if (t.length < 2 || t.length > 120) continue
                if (href.contains("/genre/") || href.contains("/type/")) continue
                mangas += SManga.create().apply {
                    title = t
                    setUrlWithoutDomain(href)
                }
                if (mangas.size >= 30) break
            }
        }
        val hasNext = doc.selectFirst("a[rel=next], .pagination a:contains(Next), .pagination a:contains(Berikutnya)") != null || mangas.size >= 20
        return MangasPage(mangas.distinctBy { it.url }, hasNext)
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        val details = parseDetails(doc).apply {
            url = manga.url
            if (title.isEmpty()) title = manga.title
            initialized = true
        }
        val chs = parseChapters(doc)
        return SMangaUpdate(details, chs)
    }

    private fun parseDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Bahasa")?.trim().orEmpty()
        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*=covers], img[src*=cover]")?.absUrl("src")
        description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".synopsis p, [class*=sinopsis] p")?.text()
        val statusText = doc.selectFirst(".comic-card-badges, .status, [class*=status]")?.text() ?: doc.body().text()
        status = when {
            statusText.contains("Ongoing", true) || statusText.contains("On Going", true) -> SManga.ONGOING
            statusText.contains("Completed", true) || statusText.contains("Tamat", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = doc.select("a[href*=genre]").joinToString { it.text().trim() }.takeIf { it.isNotEmpty() }
        author = doc.selectFirst("a[href*=author]")?.text()?.trim()
    }

    private fun parseChapters(doc: Document): List<SChapter> {
        var items = doc.select(".chapter-grid a.ch-grid-item, a.ch-grid-item")
        if (items.isEmpty()) items = doc.select("a[href*=chapter]")
        return items.mapNotNull { a ->
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            if (!href.contains("chapter")) return@mapNotNull null
            val name = a.text().trim().ifEmpty { a.attr("title").trim() }.ifEmpty { "Ch. ${a.attr("data-chapter")}" }
            if (name.isEmpty()) return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(href)
                chapter_number = a.attr("data-chapter").toFloatOrNull()
                    ?: chapterRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: Regex("""chapter/(\d+)""").find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                val dateText = a.parent()?.text() ?: a.attr("title")
                date_upload = tryParseCgbumDate(dateText)
            }
        }.distinctBy { it.url }.reversed()
    }

    private fun tryParseCgbumDate(s: String): Long {
        val t = s.lowercase(Locale.ROOT)
        return when {
            "menit lalu" in t -> {
                val n = Regex("""(\d+)\s*menit""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
                java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, -n) }.timeInMillis
            }
            "jam lalu" in t -> {
                val n = Regex("""(\d+)\s*jam""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
                java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, -n) }.timeInMillis
            }
            "hari lalu" in t -> {
                val n = Regex("""(\d+)\s*hari""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
                java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -n) }.timeInMillis
            }
            "minggu lalu" in t -> {
                val n = Regex("""(\d+)\s*minggu""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
                java.util.Calendar.getInstance().apply { add(java.util.Calendar.WEEK_OF_YEAR, -n) }.timeInMillis
            }
            else -> dateFmt.tryParseDate(s, dateFmtJakarta) ?: 0L
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        val url = getChapterUrl(chapter)
        var imgs = doc.select("#readerImages img[data-url]")
        if (imgs.isEmpty()) imgs = doc.select("#readerImages img[src]")
        if (imgs.isEmpty()) imgs = doc.select("img[data-url]")
        if (imgs.isEmpty()) imgs = doc.select(".reader-images img")
        return imgs.mapIndexedNotNull { i, img ->
            val raw = img.attr("data-url").ifEmpty { img.attr("data-src") }.ifEmpty { img.absUrl("src") }.ifEmpty { img.attr("src") }
            if (raw.isEmpty()) return@mapNotNull null
            val abs = if (raw.startsWith("http")) raw else raw
            Page(i, url, abs)
        }
    }

    override fun imageRequest(page: Page) = keiyoushi.network.GET(page.imageUrl!!, headersBuilder().add("Referer", "$baseUrl/").build())
}
