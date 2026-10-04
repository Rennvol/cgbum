package eu.kanade.tachiyomi.extension.id.mikorokux

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

@Source
abstract class MikoRokuX : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(20) { it.host == "blogger.googleusercontent.com" }
        rateLimit(2)
    }

    override fun imageRequest(page: Page): Request {
        val h = headers.newBuilder().set("Referer", "https://www.mikodrive.my.id/").build()
        return GET(page.imageUrl!!, h)
    }

    private val jsonLenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val allMangaUrl = "https://raw.githubusercontent.com/moemaomao/mymangadata/main/all-manga.json"

    // ponytail: hardcode 10 bad covers where all-manga img is "cover/..." relative — fallback to feed media$thumbnail
    private val coverFix = mapOf(
        "gokusotsu-kraken" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEjcc1Ett7Foni6PzHwFmtGowB6WR_zYP45O3Tmv2UT078MF15nlMpnVX4mi95oq1StbLKCyxdwzdh22mKoL9vo88jYYL4azhN5NE1FXeIGyehtfjUFZurfc_0wg_K_OZu3DIAFA6Ul5rhMCgm1PFjUwel87_z44olqwkTE_76V5H4uVfPV0cMpqMoIuCbs/s1600/00.jpg",
        "the-hero-wants-a-milf-as-a-reward" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEjX-q39V_OnQ7Pp8xqYn-8sIBsKZQjXAcjOHxTQzPpNlDxN1bjLRtna-Ilw6pFnK7o26HsycBtqJ8B_vVwmyF-n12UaYyqCiddwyYUmPq0klNv2WyTcyr3y1oJ7h_DHn76aMEKVjFa1SnuUdujpvk2DFJhdQ5h4fQeJjT9k1hUebVITyeelcDLdH-0R01I/s1600/00.jpg",
        "ore-wa-seikan-kokka-no-akutoku-ryoshu" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEhA-DqgtCifK8s7cqZnLmNnuyYwXVFyHYO6OEx3sEVeVaAU79bHxLCmSyVKOoU33SpHc_k4SeEZqRFZU9YwQsKQEp59ALSAewSJmfO0PZL4c-RwmeSxZA05N0QwAfIQE3fZMXOuyTglsLiuOl6sNqZgeINFMcd9nmq23YMu8FeIRq0gVomQCl9mdNfzUWk/s1600/00.jpg",
        "kichiku-eiyuu" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEhJuepRF92xJzwT6-UrDYFAEIhwrnTE4rNgF5Xm7XoonbBeFw6Zg34BVs-DSqZdjRqnhzcbMV6gaIL8TU1UgXeqRIqx7G0R4oZ5brwgeuD-Y9cUjCw0dSSaXK9xmDAsllEZk4BuZapklXQIoPUYO6qogEjQTTyX7Q3DTa7HmAj_WCosucRhx7ilfXIzTYM/s1600/00.jpg",
        "imaizumin-deep" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEgecvzoyrdva98mMTV9_-6NWRNxj4ZyFO3hBWR05_wPPbun8mTQgQXlpJ9arU0_lhcPlv1EB1pzwEhagLA8tThmCwHkSD28a44oi_Q6ODEfhxuEy3maP_W2DO_T96OWQIwMiO2Prc1effJ4qMY_MraeTlJ1YKyM1gyD_8oLEmO_NKSeWJ7-iudVuDO4A78/s1600/000000.jpg",
        "harem-ou-no-isekai-press-manyuuki" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEiyr2V6nCm-D6VevIIt3OW233-PYvhxNV5uCJRgYiknTq9a6wpH-HBJ0YVIrAaVwkXo8s3ppcNFb61VuSLjWBtfy4LQGpDcp-qbujPwCLfE-F5UPcmP7vCB6jXU_cODVsnAw0-_r8CMHXXvzClcP8YGwvxfQq8yDHVbmVwZDQ8CZqH9vOvs-DnMjXa8QXA/s1600/00.jpg",
        "hanitora-shoukan-yuusha-wo-hameru-honey-trap-hoi-mou" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEhuYvupVcug1MtVxgCAig2VAHFhQpk7sQVhM3o-qobmkDnYpwrf1EyzGO0zUhKu_mGGbA37bpjsN8KNdU5bvFFUbt4DEOg3_K1lBsi2IMTsNaYwb9mdRrxfHhrWN7nid0dTBPygV2lxPRwOUaz3ILZJg5Prj_0_mZ7e2fO5o18MuqfjriMZlFpyWCBYU3g/s1600/00.jpg",
        "ore-no-level-up-ga-okashi" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEisBJNhUr5qn4My0slFjLQ8-ktnr-g5bZGeyZd_kvsEE9pnTrzq9ETS92N8Fwh1Zgtl1GAoNSXWikvvdJE5FnNW0qzafuvnvCLJuqcAGe0Ws9f7otRC1ic8YBuKFpVPlXHf4n3Gmy_j4vEO7znHfbl74quyeEO4pj1Hc51juZG54TZgvLE6ZeBgq_aV6nQ/s1600/00.jpg",
        "kanojo-no-tomodachi" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEgHn4sMbW76c4U85E_oNLP80cqp6Zxmm4j6-PD-I3qGrinobcOT9phtUahp1mx1-wZ-G5FXPnYBhjBzQBym3IbBPNhfQWNL-e4C2nG3Rpf6NFa-ICZgc8UBobdgBmsmcIp_M99btCyAWICQKHWCWDqsibmuizgWp0gzkff8SzYG-enaNSILq9MXsMh7htQ/s1600/00.jpg",
        "sex-fantasy" to "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEijq4W48oO8G3JY76jmLLjhWnzV1f30CNnuCRjgY5pTpCAiR0KrFdvoBNP-M3HOIfkeIOER9RoKDsDgtn1EYphoFT4jhNdNnvww47pKqqHgi6mygJ2wXa1htFig0G3WCT0pPpJRCp8iX30hdJfSONhG_R_DBF46hjzI7dPgmKoBGEsgVG6L-7IO0-F3UMQ/s1600/00.jpg",
    )

    @Serializable
    data class AllManga(
        val title: String = "",
        val slug: String = "",
        val img: String = "",
        val desc: String = "",
        val genres: List<String> = emptyList(),
        val status: String = "",
        val type: String = "",
        val author: String = "",
        val artist: String = "",
    )

    private suspend fun fetchAllManga(): List<AllManga> = try {
        val req = Request.Builder().url(allMangaUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
        val txt = client.newCall(req).execute().use { it.body.string() }
        jsonLenient.decodeFromString<List<AllManga>>(txt)
    } catch (_: Exception) {
        emptyList()
    }

    private fun AllManga.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        url = "/manga/${this@toSManga.slug}"
        val raw = this@toSManga.img.takeIf { it.isNotBlank() } ?: ""
        thumbnail_url = when {
            raw.startsWith("http") -> raw
            coverFix.containsKey(this@toSManga.slug) -> coverFix[this@toSManga.slug]
            raw.isNotBlank() -> null
            else -> null
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = mangaList(page, "", null)
    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaList(page, "", null)

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(TypeFilter(), StatusFilter(), GenreFilter(), SortFilter())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = mangaList(page, query, filters)

    private suspend fun mangaList(page: Int, query: String, filters: FilterList?): MangasPage {
        val all = fetchAllManga()
        if (all.isEmpty()) return MangasPage(emptyList(), false)
        val q = query.trim().lowercase()
        val type = filters?.firstInstanceOrNull<TypeFilter>()?.toUriPart() ?: "all"
        val status = filters?.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: "all"
        val genre = filters?.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: "all"
        // sort ignored - keep original order; "popular" same, "update" reversed
        val sort = filters?.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "all"
        var list = all.asSequence()
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) || it.slug.lowercase().contains(q) }
        if (type != "all") list = list.filter { it.type.lowercase() == type }
        if (status != "all") list = list.filter { it.status.lowercase() == status }
        if (genre != "all") list = list.filter { it.genres.any { g -> g.lowercase().replace(" ", "-").contains(genre) } }
        var filtered = list.toList()
        if (sort == "update") filtered = filtered.reversed()
        val pageSize = 20
        val from = (page - 1) * pageSize
        val to = minOf(from + pageSize, filtered.size)
        if (from >= filtered.size) return MangasPage(emptyList(), false)
        val slice = filtered.subList(from, to).map { it.toSManga() }
        return MangasPage(slice, to < filtered.size)
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val slug = manga.url.substringAfterLast("/").substringBefore("?")
        var details: SManga? = null
        var chs: List<SChapter>? = null
        if (fetchDetails) {
            val all = fetchAllManga()
            val m = all.find { it.slug == slug }
            details = SManga.create().apply {
                url = manga.url
                title = m?.title ?: manga.title
                thumbnail_url = when {
                    m?.img?.startsWith("http") == true -> m.img
                    m != null && coverFix.containsKey(slug) -> coverFix[slug]
                    m?.img?.isNotBlank() == true -> manga.thumbnail_url
                    else -> m?.img ?: manga.thumbnail_url
                }
                description = m?.desc?.takeIf { it.length > 20 }
                author = m?.author?.takeIf { it.isNotBlank() && it != "-" }
                artist = m?.artist?.takeIf { it.isNotBlank() && it != "-" }
                genre = m?.genres?.joinToString(", ")
                status = when (m?.status?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        }
        if (fetchChapters) {
            val title = details?.title ?: manga.title
            chs = fetchChaptersBlogger(title)
        }
        return SMangaUpdate(details ?: manga.apply { initialized = true }, chs ?: chapters)
    }

    // Blogger feeds: mikodrive sv1 + yomidays sv2 merged dedup by chapterKey
    private suspend fun fetchChaptersBlogger(title: String): List<SChapter> {
        val q = URLEncoder.encode(title, "UTF-8")
        val feeds = listOf(
            "https://www.mikodrive.my.id/feeds/posts/default?alt=json&max-results=500&q=$q",
            "https://www.yomidays.my.id/feeds/posts/default?alt=json&max-results=500&q=$q",
        )
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SChapter>()
        for (feedUrl in feeds) {
            try {
                val txt = client.newCall(Request.Builder().url(feedUrl).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { it.body.string() }
                val root = jsonLenient.parseToJsonElement(txt)
                val entries = root.let { el ->
                    try {
                        el.let { it as kotlinx.serialization.json.JsonObject }["feed"]?.let { (it as kotlinx.serialization.json.JsonObject)["entry"] }
                    } catch (_: Exception) {
                        null
                    }
                } as? kotlinx.serialization.json.JsonArray ?: continue
                for (entryEl in entries) {
                    val obj = entryEl as? kotlinx.serialization.json.JsonObject ?: continue
                    val rawTitle = obj["title"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } } ?: continue
                    if (!rawTitle.contains("chapter", true) && !rawTitle.contains("ch.", true)) continue
                    val linkArr = obj["link"] as? kotlinx.serialization.json.JsonArray ?: continue
                    var href = ""
                    for (l in linkArr) {
                        val lo = l as? kotlinx.serialization.json.JsonObject ?: continue
                        if ((lo["rel"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "alternate") {
                            href = (lo["href"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            break
                        }
                    }
                    if (href.isEmpty()) continue
                    val num = extractChapterNumber(rawTitle, href)
                    val key = chapterKey(num)
                    if (!seen.add(key)) continue
                    val t = obj["published"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } }
                        ?: obj["updated"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } } ?: ""
                    out += SChapter.create().apply {
                        name = rawTitle
                        url = href
                        chapter_number = num.toFloatOrNull() ?: -1f
                        date_upload = try {
                            java.time.Instant.parse(t).toEpochMilli()
                        } catch (_: Exception) {
                            0L
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        if (out.isEmpty() && title.isNotBlank()) {
            val simple = title.replace(Regex("[^A-Za-z0-9 ]"), " ").trim().split(Regex("\\s+")).take(2).joinToString(" ")
            if (simple.length >= 3 && simple.lowercase() != title.lowercase()) {
                val retry = fetchChaptersBloggerSimple(simple, seen, out)
                if (retry.isNotEmpty()) return retry.sortedByDescending { it.chapter_number }
            }
        }
        return out.sortedByDescending { it.chapter_number }
    }

    private suspend fun fetchChaptersBloggerSimple(qSimple: String, seen: MutableSet<String>, out: MutableList<SChapter>): List<SChapter> {
        val q = URLEncoder.encode(qSimple, "UTF-8")
        for (feedUrl in listOf(
            "https://www.mikodrive.my.id/feeds/posts/default?alt=json&max-results=500&q=$q",
            "https://www.yomidays.my.id/feeds/posts/default?alt=json&max-results=500&q=$q",
        )) {
            try {
                val txt = client.newCall(Request.Builder().url(feedUrl).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { it.body.string() }
                val root = jsonLenient.parseToJsonElement(txt)
                val entries = root.let { el ->
                    try {
                        (el as kotlinx.serialization.json.JsonObject)["feed"]?.let { (it as kotlinx.serialization.json.JsonObject)["entry"] }
                    } catch (_: Exception) {
                        null
                    }
                } as? kotlinx.serialization.json.JsonArray ?: continue
                for (entryEl in entries) {
                    val obj = entryEl as? kotlinx.serialization.json.JsonObject ?: continue
                    val rawTitle = obj["title"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } } ?: continue
                    if (!rawTitle.contains("chapter", true) && !rawTitle.contains("ch.", true)) continue
                    val linkArr = obj["link"] as? kotlinx.serialization.json.JsonArray ?: continue
                    var href = ""
                    for (l in linkArr) {
                        val lo = l as? kotlinx.serialization.json.JsonObject ?: continue
                        if ((lo["rel"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "alternate") {
                            href = (lo["href"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            break
                        }
                    }
                    if (href.isEmpty()) continue
                    val num = extractChapterNumber(rawTitle, href)
                    if (!seen.add(chapterKey(num))) continue
                    val tt = obj["published"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } } ?: obj["updated"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("\$t")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } } ?: ""
                    out += SChapter.create().apply {
                        name = rawTitle
                        url = href
                        chapter_number = num.toFloatOrNull() ?: -1f
                        date_upload = try {
                            java.time.Instant.parse(tt).toEpochMilli()
                        } catch (_: Exception) {
                            0L
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun chapterKey(num: String): String {
        val n = num.replace(Regex("[^0-9.]"), "").toFloatOrNull()
        return if (n != null) n.toString() else num.trim().lowercase()
    }

    private fun extractChapterNumber(rawTitle: String, linkUrl: String): String {
        val t = rawTitle.trim()
        val url = linkUrl
        fun norm(s: String) = s.trim().replace(Regex("[_-]"), ".").replace(Regex("\\.+"), ".").trim('.')
        fun hasDec(s: String) = Regex("\\d+\\.\\d+").containsMatchIn(s)
        var urlNum: String? = null
        val mUrl = Regex("""chapter[_-]?(\\d+(?:[.-]\\d+)?)""", RegexOption.IGNORE_CASE).find(url)
            ?: Regex("""ch[_-]?(\\d+(?:[.-]\\d+)?)""", RegexOption.IGNORE_CASE).find(url)
        if (mUrl != null) urlNum = norm(mUrl.groupValues[1])
        var titleNum: String? = null
        val pats = listOf(
            Regex("""(?:chapter|ch\\.?|chap\\.?|ep\\.?|episode)\\s*[:\\-]?\\s*(\\d+(?:[.,]\\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""#\\s*(\\d+(?:[.,]\\d+)?)"""),
        )
        for (re in pats) {
            val m = re.find(t)
            if (m != null) {
                titleNum = norm(m.groupValues[1].replace(",", "."))
                break
            }
        }
        if (titleNum == null) {
            val all = Regex("""(\\d+(?:[.,]\\d+)?)""").findAll(t).map { it.groupValues[1] }.toList()
            if (all.isNotEmpty()) titleNum = norm(all.last().replace(",", "."))
        }
        if (titleNum != null && hasDec(titleNum)) return titleNum
        if (urlNum != null && hasDec(urlNum)) return urlNum
        if (titleNum != null) return titleNum
        if (urlNum != null) return urlNum
        return "?"
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter)
        // chapter url is Blogger post URL; fetch and extract <img src>
        return try {
            val doc = client.get(url).asJsoup()
            var els = doc.select("div.separator a[href] img[src]")
            if (els.isEmpty()) els = doc.select("img[src*=blogger.googleusercontent]")
            if (els.isEmpty()) els = doc.select("#readerImages [data-url]")
            if (els.isEmpty()) els = doc.select("img[src]")
            els.mapIndexedNotNull { i, el ->
                val raw = el.absUrl("src").ifEmpty { el.attr("data-url") }.ifEmpty { el.absUrl("data-src") }
                if (raw.isEmpty() || raw.contains("favicon") || raw.contains("logo")) return@mapIndexedNotNull null
                // filter to chapter images: must contain blogger or image host
                if (!raw.contains("blogger") && !raw.contains("lh3") && !raw.contains("images.weserv")) {
                    // keep only if inside separator/post body
                    if (!el.parents().any { it.hasClass("separator") || it.tagName() == "article" }) return@mapIndexedNotNull null
                }
                Page(i, url, raw)
            }.distinctBy { it.imageUrl }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
