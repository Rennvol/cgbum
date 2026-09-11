package eu.kanade.tachiyomi.extension.id.magadaxx

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Instant

@Source
abstract class MagaDaxX : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply { rateLimit(3) }

    private val mangaId = "c2c8e42b-b242-4c69-ad99-6b3ce4ad55de"
    private val apiBase = "https://api.mangadex.org"

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val url = "$apiBase/manga/$mangaId".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .build()
        val dto = client.get(url).parseAs<MangaResponse>()
        val data = dto.data ?: return MangasPage(emptyList(), false)
        return MangasPage(listOf(data.toSManga()), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val q = query.trim()
        if (q.isEmpty()) return getPopularManga(1)
        val url = "$apiBase/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", q)
            .addQueryParameter("ids[]", mangaId)
            .addQueryParameter("availableTranslatedLanguage[]", "id")
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("limit", "10")
            .build()
        val dto = client.get(url).parseAs<MangaListResponse>()
        return MangasPage(dto.data.map { it.toSManga() }, false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var details: SManga? = null
        var chList: List<SChapter>? = null
        if (fetchDetails) {
            val url = "$apiBase/manga/$mangaId".toHttpUrl().newBuilder()
                .addQueryParameter("includes[]", "cover_art")
                .addQueryParameter("includes[]", "author")
                .addQueryParameter("includes[]", "artist")
                .build()
            val dto = client.get(url).parseAs<MangaResponse>()
            dto.data?.let { details = it.toSMangaDetails() }
        }
        if (fetchChapters) {
            val url = "$apiBase/manga/$mangaId/feed".toHttpUrl().newBuilder()
                .addQueryParameter("translatedLanguage[]", "id")
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("order[volume]", "desc")
                .addQueryParameter("limit", "500")
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addQueryParameter("includeEmptyPages", "0")
                .build()
            val dto = client.get(url).parseAs<ChapterListResponse>()
            chList = dto.data
                .filter { it.attributes?.chapter != null }
                .map { it.toSChapter() }
                .sortedByDescending { it.chapter_number }
        }
        return SMangaUpdate(details ?: manga, chList ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val url = "$apiBase/at-home/server/$chapterId".toHttpUrl()
        val dto = client.get(url, CacheControl.FORCE_NETWORK).parseAs<AtHomeResponse>()
        val host = dto.baseUrl
        val hash = dto.chapter.hash
        return dto.chapter.data.mapIndexed { i, file ->
            Page(i, "", "$host/data/$hash/$file")
        }
    }

    override fun getMangaUrl(manga: SManga): String = "https://mangadex.org/title/$mangaId/tensei-nihakobinin-no-isekai-kouryakuhou"

    override fun getChapterUrl(chapter: SChapter): String = "https://mangadex.org" + chapter.url

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(headers).build()

    private fun MangaData.toSManga(): SManga = SManga.create().apply {
        url = "/manga/$id"
        val attrs = attributes
        title = attrs?.title?.get("id") ?: attrs?.title?.get("en") ?: attrs?.title?.values?.firstOrNull() ?: id
        thumbnail_url = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName?.let { fn ->
            "https://uploads.mangadex.org/covers/$id/$fn.512.jpg"
        }
    }

    private fun MangaData.toSMangaDetails(): SManga = SManga.create().apply {
        url = "/manga/$id"
        val attrs = attributes
        title = attrs?.title?.get("id") ?: attrs?.title?.get("en") ?: attrs?.title?.values?.firstOrNull() ?: id
        val desc = attrs?.description?.get("id") ?: attrs?.description?.get("en") ?: ""
        val alt = attrs?.altTitles?.mapNotNull { it["id"] ?: it["en"] }?.joinToString(", ") ?: ""
        description = buildString {
            if (desc.isNotBlank()) append(desc)
            if (alt.isNotBlank()) {
                if (isNotEmpty()) append("\n\nAlt: $alt")
            }
        }
        status = when (attrs?.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = attrs?.tags?.mapNotNull { it.attributes?.name?.get("en") }?.joinToString(", ")
        thumbnail_url = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName?.let { fn ->
            "https://uploads.mangadex.org/covers/$id/$fn.512.jpg"
        }
    }

    private fun ChapterData.toSChapter(): SChapter = SChapter.create().apply {
        val a = attributes
        url = "/chapter/$id"
        val vol = a?.volume?.takeIf { it.isNotBlank() }?.let { "Vol.$it " } ?: ""
        val ch = a?.chapter?.takeIf { it.isNotBlank() }?.let { "Ch.$it" } ?: "Oneshot"
        val t = a?.title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        name = "$vol$ch$t".trim()
        date_upload = a?.publishAt?.let { Instant.tryParse(it) } ?: 0L
        chapter_number = a?.chapter?.toFloatOrNull() ?: -1f
        scanlator = relationships.firstOrNull { it.type == "scanlation_group" }?.attributes?.name
    }

    @Serializable
    class MangaResponse(val result: String = "", val data: MangaData? = null)

    @Serializable
    class MangaListResponse(val result: String = "", val data: List<MangaData> = emptyList())

    @Serializable
    class MangaData(
        val id: String = "",
        val attributes: MangaAttributes? = null,
        val relationships: List<Relation> = emptyList(),
    )

    @Serializable
    class MangaAttributes(
        val title: Map<String, String> = emptyMap(),
        val altTitles: List<Map<String, String>> = emptyList(),
        val description: Map<String, String> = emptyMap(),
        val status: String? = null,
        val tags: List<Tag> = emptyList(),
    )

    @Serializable
    class Tag(val attributes: TagAttr? = null)

    @Serializable
    class TagAttr(val name: Map<String, String> = emptyMap())

    @Serializable
    class Relation(
        val id: String = "",
        val type: String = "",
        val attributes: RelationAttr? = null,
    )

    @Serializable
    class RelationAttr(
        val fileName: String? = null,
        val name: String? = null,
    )

    @Serializable
    class ChapterListResponse(val result: String = "", val data: List<ChapterData> = emptyList())

    @Serializable
    class ChapterData(
        val id: String = "",
        val attributes: ChapterAttr? = null,
        val relationships: List<Relation> = emptyList(),
    )

    @Serializable
    class ChapterAttr(
        val title: String? = null,
        val volume: String? = null,
        val chapter: String? = null,
        val publishAt: String? = null,
    )

    @Serializable
    class AtHomeResponse(val baseUrl: String = "", val chapter: AtHomeChapter = AtHomeChapter())

    @Serializable
    class AtHomeChapter(val hash: String = "", val data: List<String> = emptyList())
}
