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
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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
        var lastWasMaintenance = false
        repeat(RETRIES) { attempt ->
            val body = try {
                client.get(url, headersBuilder().add("Accept", "image/webp,image/apng").build(), ensureSuccess = false)
                    .body
                    .string()
            } catch (e: SiteUnavailableException) {
                // Сеть/сервер недоступны — повторять бессмысленно, показываем причину сразу.
                throw e
            } catch (e: IOException) {
                if (attempt == RETRIES - 1) throw SiteUnavailableException(networkFailureText(e))
                delay(RETRY_DELAY_MS * (attempt + 1))
                return@repeat
            }

            when {
                body.isAccessDenied() -> throw IOException("Нужна авторизация: войдите на hentaichan.live через WebView и повторите")
                !body.isBrokenPage() -> return body
                else -> {
                    lastWasMysqlError = body.isMysqlError()
                    lastWasMaintenance = body.isMaintenance()
                    if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }

        throw IOException(
            when {
                lastWasMysqlError -> "Сайт отдаёт MySQL-ошибку счётчика просмотров, попробуйте позже"
                lastWasMaintenance -> "Сайт на техническом обслуживании, попробуйте позже"
                else -> "Сайт вернул пустую страницу"
            },
        )
    }

    /**
     * Сайт периодически отваливается целиком (сброс соединения), и в этом случае хочется
     * видеть причину, а не `Connection reset`. Внешний сервис проверки доступности здесь
     * не подходит: downforeveryoneorjustme.com рендерит вердикт на клиенте и API не отдаёт.
     */
    // Таймауты и раздельный rate limit выровнены с MultiManga: мёртвый CDN-редирект
    // висит на TLS/чтении, поэтому connect 8 с и read 10 с; картинки сайта живут на
    // *.imgschan.xyz, им можно чаще, чем HTML-страницам.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .rateLimit(6) { it.host.endsWith(IMAGE_HOST_SUFFIX) }
        .rateLimit(2)
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: SiteUnavailableException) {
                throw e
            } catch (e: IOException) {
                // Отмену запроса приложением не подменяем на «сайт недоступен».
                if (e is InterruptedIOException && e !is SocketTimeoutException) throw e
                throw SiteUnavailableException(networkFailureText(e), e)
            }
        }
        // Network-интерсептор обрывает редирект на уже известный мёртвый хост, не дожидаясь
        // таймаута; заглушка внутри обёртки выше подменяет и HTTP-ошибки, и сетевые сбои.
        .addNetworkInterceptor(DeadRedirectInterceptor())
        .addInterceptor(PageImageFallbackInterceptor())

    /**
     * Сайт отдаёт список страниц пачкой URL, и часть из них потом не скачивается: 404,
     * 5xx или HTML-заглушка с кодом 200. Клиент видит только ответ картинки, поэтому
     * помечаем такие запросы — интерсептор подменит битый ответ изображением-заглушкой,
     * и глава скачается целиком.
     */
    override fun imageRequest(page: Page): Request = super.imageRequest(page)
        .newBuilder()
        .tag(PageImageMarker::class.java, PageImageMarker)
        .build()

    private fun networkFailureText(error: IOException): String {
        val host = URL(baseUrl).host
        return when (error) {
            is UnknownHostException -> "Не удалось найти адрес $host — проверьте подключение к интернету"
            is SocketTimeoutException -> "Сайт $host не ответил вовремя — попробуйте позже"
            is SocketException -> "Соединение с $host сброшено: похоже, сайт недоступен или блокируется"
            else -> "Сайт $host не отвечает: ${error.message ?: "сетевая ошибка"}"
        }
    }

    // Сетевые сбои не глотаем: пользователь должен увидеть причину, а не пустую карточку.
    private suspend fun <T> orNullOnBrokenPage(block: suspend () -> T): T? = try {
        block()
    } catch (e: SiteUnavailableException) {
        throw e
    } catch (e: IOException) {
        null
    }

    private fun String.isMysqlError() = contains("MySQL Error") || contains("Error Number: 1114")

    private fun String.isAccessDenied() = length < DENIED_PAGE_MAX_LENGTH && contains("404 not found")

    private fun String.isMaintenance() = contains("Технические работы", ignoreCase = true) || contains("technical maintenance", ignoreCase = true)

    private fun String.isBrokenPage() = length < BROKEN_PAGE_MAX_LENGTH || isMysqlError() || isMaintenance()

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
        manga.thumbnail_url = element.selectFirst("img")?.imageUrl()?.getHQThumbnail()
        return manga
    }

    // Раздел «популярное» на сайте отдаёт заглушку «Технические работы», поэтому в этом случае
    // показываем новинки: пустая вкладка хуже, чем список с другой сортировкой.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = 20 * (page - 1)
        val body = client.get("$baseUrl/mostfavorites?offset=$offset", headersBuilder().build(), ensureSuccess = false)
            .body
            .string()

        if (body.isBrokenPage() || body.isAccessDenied()) {
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
        val tags = document.parseTags()
        manga.genre = (listOfNotNull(category) + tags)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        manga.description = document.selectFirst("div#description")
            ?.wholeText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        manga.thumbnail_url = document.selectFirst("img#cover")?.imageUrl()?.getHQThumbnail()
        return manga
    }

    private fun String.cleanText() = replace("\uFEFF", "").replace("\u00A0", " ").trim()

    /**
     * `attr("abs:src")` у пустого `src` возвращает не пустую строку, а base URI страницы:
     * тогда обложкой становится адрес самой карточки, и хост качает HTML вместо картинки.
     * Поэтому сначала проверяем сырое значение, и только потом разворачиваем его в абсолютный URL.
     */
    private fun Element.imageUrl(): String? = attr("src")
        .takeIf { it.isNotBlank() }
        ?.let { absUrl("src").ifBlank { it } }

    private fun Element.cleanText() = text().cleanText()

    // В каждой строке тегов по три ссылки: «+» и «−» для поиска и сам тег последним.
    private fun Document.parseTags() = select(".sidetags li.sidetag a:last-child")
        .map { it.cleanText() }
        .filter { it.isNotEmpty() && it != "+" && it != "-" }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detailsPage = orNullOnBrokenPage { fetchText(baseUrl + manga.url).asJsoup(baseUrl) }

        val details = detailsPage?.let { mangaDetailsParse(it) } ?: mangaDetailsFallback(manga)
        details.url = manga.url

        // У части тайтлов сайт отдаёт карточку с пустым src у обложки, тогда берём первую
        // миниатюру со страницы чтения — она есть всегда, когда есть страницы.
        if (details.thumbnail_url.isNullOrBlank()) {
            details.thumbnail_url = coverFromReaderPage(manga.url)
        }

        val chapterList = fetchChapterList(details, detailsPage ?: Document(baseUrl))

        // Эталон поведения — MultiManga: у части серии в библиотеке хранится название
        // серии без «глава N», а имя главы — суффикс маркера.
        ChapterTitles.parse(details.title).base?.let { details.title = it }

        return SMangaUpdate(details, chapterList)
    }

    private suspend fun coverFromReaderPage(mangaUrl: String): String? {
        val url = baseUrl + mangaUrl.replace("/manga/", "/online/")
        val body = orNullOnBrokenPage { fetchText(url) } ?: return null
        // Полноразмерная страница даёт читаемую обложку, миниатюра — только запасной вариант:
        // 100x140 в библиотеке выглядит мылом.
        val block = fullimgRegex.find(body)?.groupValues?.get(1)
            ?: thumbsRegex.find(body)?.groupValues?.get(1)
            ?: return null
        return imageUrlRegex.find(block.replace("\\/", "/"))?.value
    }

    // Пока счётчик просмотров лежит, карточку собираем из страниц, которые не задевают
    // счётчик: заголовок и обложка есть на /related/, остальное остаётся пустым.
    private suspend fun mangaDetailsFallback(manga: SManga): SManga {
        val related = orNullOnBrokenPage {
            fetchText(baseUrl + manga.url.replace("/manga/", "/related/")).asJsoup(baseUrl)
        }

        val details = SManga.create()
        details.url = manga.url
        details.title = related?.title()
            ?.substringAfter("похожий на ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: manga.title
        details.thumbnail_url = related?.selectFirst("img#cover")?.imageUrl()?.getHQThumbnail()
        details.genre = related?.parseTags()?.joinToString(", ")
        return details
    }

    override suspend fun fetchChapterList(manga: SManga, mangaPage: Document): List<SChapter> {
        if (manga.thumbnail_url?.endsWith("#") == true) {
            return listOf(manga.singleChapter(mangaPage))
        }

        val relatedUrl = baseUrl + manga.url.replace("/manga/", "/related/")
        val body = orNullOnBrokenPage { fetchText(relatedUrl) } ?: return listOf(manga.singleChapter())

        return chapterListParse(body.asJsoup(relatedUrl), manga, mangaPage)
    }

    override fun chapterListSelector() = ".related"

    private suspend fun chapterListParse(document: Document, manga: SManga, currentPage: Document?): List<SChapter> {
        val related = relatedEntries(document).toMutableList()

        var nextElement = document.selectFirst("div#pagination_related a:contains(Вперед)")
        while (nextElement != null) {
            val url = nextElement.attr("abs:href")
            if (url.isEmpty()) break

            val nextPage = fetchText(url).asJsoup(url)
            related.addAll(relatedEntries(nextPage))

            nextElement = nextPage.selectFirst("div#pagination_related a:contains(Вперед)")
        }

        // Вкладка «Похожая манга» — это другие работы, а не главы. Главами они являются
        // только у сериалов, где каждая часть заведена отдельной записью с тем же названием.
        val series = ChapterTitles.parse(manga.title).base?.let(ChapterTitles::normalizeBase)
        val chapters = if (series == null) {
            emptyList()
        } else {
            related
                .filter { (_, fullTitle) ->
                    ChapterTitles.parse(fullTitle).base?.let(ChapterTitles::normalizeBase) == series
                }
                .map { (chapter, _) -> chapter }
        }

        return (chapters + manga.singleChapter(currentPage))
            .distinctBy { it.url }
            .sortedBy { it.chapter_number }
    }

    // Полное название нужно для сравнения серий, а в имя главы кладём короткий суффикс.
    private fun relatedEntries(document: Document): List<Pair<SChapter, String>> = document.select(chapterListSelector()).map { element ->
        val fullTitle = element.selectFirst("h2 a")?.attr("title").orEmpty()
        chapterFromElement(element) to fullTitle
    }

    private fun SManga.singleChapter(page: Document? = null): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(url)
        val parts = ChapterTitles.parse(title)
        chapter.name = parts.label ?: "Chapter"
        chapter.chapter_number = parts.number ?: 1F
        chapter.date_upload = page?.let { chapterDate(it) } ?: 0L
        return chapter
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val aElement = element.selectFirst("h2 a")
        chapter.setUrlWithoutDomain(aElement?.attr("abs:href") ?: "")
        val fullTitle = aElement?.attr("title") ?: ""
        val parts = ChapterTitles.parse(fullTitle)
        chapter.name = parts.label ?: fullTitle
        chapter.chapter_number = parts.number ?: -1F
        return chapter
    }

    private fun chapterDate(document: Document): Long = document.selectFirst("time[datetime]")
        ?.attr("datetime")
        ?.trim()
        ?.let { DATE_FORMAT.tryParseDateTime(it) }
        ?: 0L

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

        private const val IMAGE_HOST_SUFFIX = "imgschan.xyz"

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val manganewThumbsRegex = "(?<=/)manganew_thumbs\\w*?(?=/)".toRegex(RegexOption.IGNORE_CASE)
        private val fullimgRegex = """["']?fullimg["']?\s*[:=]\s*\[([^]]*)]""".toRegex()
        private val thumbsRegex = """["']?thumbs["']?\s*[:=]\s*\[([^]]*)]""".toRegex()
        private val urlRegex = """https?://[^'"\s\\]+""".toRegex()
        private val imageUrlRegex = """https?://[^'"\s\\]+\.(?:jpe?g|png|webp|gif)""".toRegex(RegexOption.IGNORE_CASE)
    }
}

/** Сайт недоступен целиком: сеть, DNS, таймаут или сброс соединения. */
class SiteUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)
