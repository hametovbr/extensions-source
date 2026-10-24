package eu.kanade.tachiyomi.extension.ru.henchan

import eu.kanade.tachiyomi.multisrc.multichan.MultiChan
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URL

@Source
abstract class HenChan : MultiChan() {

    override fun latestUpdatesUrl(page: Int) = "$baseUrl/manga/newest?offset=${20 * (page - 1)}"

    override fun searchMangaUrl(page: Int, query: String, filters: FilterList): String {
        if (query.isNotEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("do", "search")
                .addQueryParameter("subaction", "search")
                .addQueryParameter("story", query)
                .addQueryParameter("search_start", page.toString())
                .build()
                .toString()
            return url
        }

        var genres = ""

        filters.forEach { filter ->
            if (filter is GenreList) {
                filter.state
                    .filter { !it.isIgnored() }
                    .forEach { f ->
                        genres += (if (f.isExcluded()) "-" else "") + f.id + '+'
                    }
            }
        }

        val orderBy = filters.firstInstanceOrNull<OrderBy>()
        val url = if (genres.isNotEmpty()) {
            val order = orderBy?.toUriPartWithGenres() ?: ""
            "$baseUrl/tags/${genres.dropLast(1)}&sort=manga$order?offset=${20 * (page - 1)}"
        } else {
            val order = orderBy?.toUriPartWithoutGenres() ?: ""
            "$baseUrl/$order?offset=${20 * (page - 1)}"
        }

        return url
    }

    override fun searchMangaSelector() = ".content_row:not(:has(div.item:containsOwn(Тип)))"

    /**
     * Сайт периодически ломается на счётчике просмотров (`hentai_views is full`) и отдаёт
     * HTTP 200 с килобайтной страницей ошибки вместо контента, а закрытые разделы — с заглушкой
     * `404 not found;`. И то и другое легко принять за валидный документ, поэтому все запросы
     * идут через эту обёртку.
     */
    private suspend fun fetchText(url: String): String {
        var lastWasMysqlError = false
        repeat(RETRIES) { attempt ->
            val body = client.get(url, headersBuilder().add("Accept", "image/webp,image/apng").build(), ensureSuccess = false)
                .body
                .string()

            when {
                body.isAccessDenied() -> throw IOException("Нужна авторизация: войдите на hentaichan.live через WebView и повторите")
                !body.isBrokenPage() -> return body
                else -> {
                    lastWasMysqlError = body.isMysqlError()
                    if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }

        throw IOException(
            if (lastWasMysqlError) {
                "Сайт отдаёт MySQL-ошибку счётчика просмотров, попробуйте позже"
            } else {
                "Сайт вернул пустую страницу"
            },
        )
    }

    private fun String.isMysqlError() = contains("MySQL Error") || contains("Error Number: 1114")

    private fun String.isAccessDenied() = length < DENIED_PAGE_MAX_LENGTH && contains("404 not found")

    private fun String.isBrokenPage() = length < BROKEN_PAGE_MAX_LENGTH || isMysqlError()

    private fun String.getHQThumbnail(): String {
        val isExHenManga = contains("/manganew_thumbs_blur/")
        return replace(manganewThumbsRegex, "showfull_retina/manga")
            .replace(
                "_".plus(URL(baseUrl).host),
                "_hentaichan.ru",
            ) // domain-related replacing for very old mangas
            .plus(if (isExHenManga) "#" else "")
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = super.popularMangaFromElement(element)
        manga.thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.getHQThumbnail()
        return manga
    }

    // Раздел «популярное» на сайте отдаёт заглушку «Технические работы», поэтому в этом случае
    // показываем новинки: пустая вкладка хуже, чем список с другой сортировкой.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = 20 * (page - 1)
        val body = client.get("$baseUrl/mostfavorites?offset=$offset", headersBuilder().build(), ensureSuccess = false)
            .body
            .string()

        if (body.isBrokenPage() || body.isAccessDenied() || body.contains("Технические работы")) {
            return mangasPage("$baseUrl/manga/newest?offset=$offset")
        }

        return mangasPage(body.asJsoup(baseUrl))
    }

    private suspend fun mangasPage(url: String): MangasPage = mangasPage(fetchText(url).asJsoup(url))

    private fun mangasPage(document: Document): MangasPage {
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNextPage = document.select(popularMangaNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.title().substringBefore(" »").trim()

        val rows = document.select("#info_wrap div.row, #info_wrap tr")
        manga.author = rows.firstOrNull { it.text().contains("Автор") }
            ?.selectFirst(".item2")
            ?.cleanText()

        // Метки «Тип» на странице больше нет: категория лежит значением в первой строке.
        val category = rows.firstOrNull { it.text().contains("Аниме/манга") }
            ?.text()
            ?.substringAfter("Аниме/манга")
            ?.cleanText()
        val tags = document.select(".sidetags ul a").map { it.cleanText() }
        manga.genre = (listOfNotNull(category) + tags)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        manga.description = document.selectFirst("div#description")
            ?.wholeText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        manga.thumbnail_url = document.selectFirst("img#cover")?.attr("abs:src")?.getHQThumbnail()
        return manga
    }

    private fun String.cleanText() = replace("\uFEFF", "").replace("\u00A0", " ").trim()

    private fun Element.cleanText() = text().cleanText()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detailsPage = runCatching { fetchText(baseUrl + manga.url).asJsoup(baseUrl) }.getOrNull()

        val details = detailsPage?.let { mangaDetailsParse(it) } ?: mangaDetailsFallback(manga)
        return SMangaUpdate(details, fetchChapterList(manga, detailsPage ?: Document(baseUrl)))
    }

    // Пока счётчик просмотров лежит, карточку собираем из страниц, которые не задевают
    // счётчик: заголовок и обложка есть на /related/, остальное остаётся пустым.
    private suspend fun mangaDetailsFallback(manga: SManga): SManga {
        val related = runCatching { fetchText(baseUrl + manga.url.replace("/manga/", "/related/")).asJsoup(baseUrl) }
            .getOrNull()

        val details = SManga.create()
        details.url = manga.url
        details.title = related?.title()
            ?.substringAfter("похожий на ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: manga.title
        details.thumbnail_url = related?.selectFirst("img#cover")?.attr("abs:src")?.getHQThumbnail()
        details.genre = related?.select(".sidetags ul a")
            ?.map { it.cleanText() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
        return details
    }

    override suspend fun fetchChapterList(manga: SManga, mangaPage: Document): List<SChapter> {
        if (manga.thumbnail_url?.endsWith("#") == true) {
            return listOf(manga.singleChapter(mangaPage))
        }

        val relatedUrl = baseUrl + manga.url.replace("/manga/", "/related/")
        val body = runCatching { fetchText(relatedUrl) }
            .getOrElse { return listOf(manga.singleChapter()) }

        return chapterListParse(body.asJsoup(relatedUrl), manga)
    }

    override fun chapterListSelector() = ".related"

    private suspend fun chapterListParse(document: Document, manga: SManga): List<SChapter> {
        val result = document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .toMutableList()

        // У части тайтлов related-глав нет, и тогда единственная глава — сама манга.
        if (result.isEmpty()) return listOf(manga.singleChapter())

        var nextElement = document.selectFirst("div#pagination_related a:contains(Вперед)")
        while (nextElement != null) {
            val url = nextElement.attr("abs:href")
            if (url.isEmpty()) break

            val nextPage = fetchText(url).asJsoup(url)
            result.addAll(
                nextPage.select(chapterListSelector()).map { chapterFromElement(it) },
            )

            nextElement = nextPage.selectFirst("div#pagination_related a:contains(Вперед)")
        }

        return result.reversed()
    }

    private fun SManga.singleChapter(page: Document? = null): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(url)
        chapter.name = page?.selectFirst("a.title_top_a")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: "Chapter"
        chapter.chapter_number = chapterNumberRegex.find(chapter.name)
            ?.groupValues
            ?.get(2)
            ?.toFloatOrNull()
            ?: 1F
        return chapter
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val aElement = element.selectFirst("h2 a")
        chapter.setUrlWithoutDomain(aElement?.attr("abs:href") ?: "")
        val chapterName = aElement?.attr("title") ?: ""
        chapter.name = chapterName
        chapter.chapter_number = chapterNumberRegex.find(chapterName)?.groupValues?.get(2)?.toFloat() ?: -1F
        return chapter
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = if (chapter.url.contains("/manga/")) {
            baseUrl + chapter.url.replace("/manga/", "/online/")
        } else {
            baseUrl + chapter.url
        }
        return pageListParse(fetchText(url))
    }

    override fun pageListParse(html: String): List<Page> {
        val block = fullimgRegex.find(html)?.groupValues?.get(1)
            ?: throw IOException("Не удалось найти список страниц")
        val urls = urlRegex.findAll(block.replace("\\/", "/"))
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()

        if (urls.isEmpty()) throw IOException("Список страниц пуст")
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        OrderBy(),
        GenreList(getGenreList()),
    )

    companion object {
        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 700L
        private const val BROKEN_PAGE_MAX_LENGTH = 2000
        private const val DENIED_PAGE_MAX_LENGTH = 200

        private val manganewThumbsRegex = "(?<=/)manganew_thumbs\\w*?(?=/)".toRegex(RegexOption.IGNORE_CASE)
        private val chapterNumberRegex = "(глава\\s|часть\\s)([0-9]+\\.?[0-9]*)".toRegex(RegexOption.IGNORE_CASE)
        private val fullimgRegex = """["']?fullimg["']?\s*[:=]\s*\[([^]]*)]""".toRegex()
        private val urlRegex = """https?://[^'"\s\\]+""".toRegex()
    }
}
