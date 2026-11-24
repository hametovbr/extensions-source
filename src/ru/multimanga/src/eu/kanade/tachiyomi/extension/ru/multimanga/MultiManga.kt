package eu.kanade.tachiyomi.extension.ru.multimanga

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
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Source
abstract class MultiManga : KeiSource() {

    // Мёртвый CDN-редирект (узнаём мы его только после первого сбоя) висит на TLS-обмене
    // и чтении, поэтому таймауты снижены с 30 с: connect 8 с, read 10 с. Одна картинка —
    // это ~250 КБ, такие чтения укладываются в 10 с с запасом, а HTML-запросы повторяются
    // до трёх раз и коротких таймаутов не боятся.
    //
    // Сайт отдаёт 25 карточек на страницу, а главы — десятки картинок: на общем лимите
    // 2 запроса/с только чтение 22 страниц занимает минимум 11 секунд. Картинки живут на
    // отдельном CDN, поэтому для них лимит выше; остальные хосты — как у сайта.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .rateLimit(6) {
            it.host.endsWith(IMAGE_HOST_SUFFIX)
        }
        .rateLimit(2) { it.host.endsWith(SITE_HOST_SUFFIX) }
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
        // Сетевой интерсептор ниже обрывает редирект на уже известный мёртвый хост,
        // не дожидаясь 30-секундного таймаута; заглушка ставится мгновенно.
        .addNetworkInterceptor(DeadRedirectInterceptor())
        // Внутри обёртки выше: сетевой сбой заглушка не маскирует, он по-прежнему
        // превращается в понятную причину «сайт недоступен».
        .addInterceptor(PageImageFallbackInterceptor())

    // Сайт отдаёт прямые ссылки на страницы, но часть файлов CDN потом не скачивается
    // (301 на мёртвый хост, 502, HTML с кодом 200). Помечаем запросы картинок, чтобы
    // интерсептор подменил битый ответ изображением-заглушкой.
    override fun imageRequest(page: Page): Request = super.imageRequest(page)
        .newBuilder()
        .tag(PageImageMarker::class.java, PageImageMarker)
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = getLatestUpdates(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = fetchDocument("$baseUrl/page/$page/")
        return MangasPage(
            document.select(CARD_SELECTOR).map { mangaFromElement(it) },
            document.selectFirst(NEXT_PAGE) != null,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty()) {
            val document = fetchDocument(searchUrl(page, query))
            return MangasPage(
                document.select(CARD_SELECTOR).map { mangaFromElement(it) },
                document.selectFirst(SEARCH_NEXT) != null,
            )
        }

        val tags = filters.firstInstanceOrNull<GenreList>()
            ?.state
            ?.filter { it.isIncluded() }
            ?.map { it.id }
            ?.takeIf { it.isNotEmpty() }
            ?: return getLatestUpdates(page)

        val document = fetchDocument(tagsUrl(page, tags))
        return MangasPage(
            document.select(CARD_SELECTOR).map { mangaFromElement(it) },
            document.selectFirst(NEXT_PAGE) != null,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchDocument(baseUrl + manga.url)
        val details = parseDetails(document)
        details.url = manga.url
        val date = chapterDate(document)

        // Запись = одна галерея; «глава N» — часть названия. Если маркера в названии нет,
        // отдаём одну синтетическую главу, иначе собираем части серии поиском по базе.
        val originalTitle = details.title
        val (base, number) = splitChapterTitle(originalTitle)
        val chapterList = if (base == null || number == null) {
            listOf(singleChapter(manga.url, originalTitle, date))
        } else {
            // В библиотеку сохраняем название серии без «глава N»: у каждой части иначе
            // получается своя запись с длинным названием конкретной главы.
            details.title = base
            groupedChapters(manga.url, originalTitle, base, number, date)
        }

        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = baseUrl + chapter.url
        val document = fetchDocument(url)

        val galleryPages = document.select("#thumbnail-container a.gallerythumb img")
            .mapNotNull { it.lazyImageUrl() }
        if (galleryPages.isNotEmpty()) {
            return galleryPages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        }

        // Резерв: если галерея на странице пустая или урезанная, список собираем читалкой —
        // по одному запросу на страницу, зато глава остаётся читаемой.
        val readerPages = readerPages(url, document)
        if (readerPages.isEmpty()) throw IOException("Не удалось получить список страниц")
        return readerPages
    }

    override fun getFilterList(data: JsonElement?) = FilterList(GenreList(getGenreList()))

    /**
     * Сайт периодически отдаёт HTTP 200 с короткой страницей `MySQL Error` вместо контента,
     * поэтому все запросы идут через эту обёртку с повторами.
     */
    private suspend fun fetchText(url: String): String {
        var lastWasMysqlError = false
        repeat(RETRIES) { attempt ->
            val body = try {
                client.get(url, headers, ensureSuccess = false).body.string()
            } catch (e: SiteUnavailableException) {
                // Сеть недоступна — повторять бессмысленно, показываем причину сразу.
                throw e
            } catch (e: IOException) {
                if (attempt == RETRIES - 1) throw SiteUnavailableException(networkFailureText(e), e)
                delay(RETRY_DELAY_MS * (attempt + 1))
                return@repeat
            }

            if (!body.isBrokenPage()) return body
            lastWasMysqlError = body.isMysqlError()
            if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }

        throw SiteUnavailableException(
            if (lastWasMysqlError) {
                "Сайт отдаёт ошибку базы данных, попробуйте позже"
            } else {
                "Сайт вернул пустую страницу"
            },
        )
    }

    private suspend fun fetchDocument(url: String): Document = fetchText(url).asJsoup(url)

    private fun searchUrl(page: Int, query: String): String = baseUrl.toHttpUrl().newBuilder()
        .addQueryParameter("do", "search")
        .addQueryParameter("subaction", "search")
        .addQueryParameter("story", query)
        .addQueryParameter("search_start", page.toString())
        .build()
        .toString()

    private fun tagsUrl(page: Int, tags: List<String>): String {
        // Пробел кодируется как `+`, а не `%20`: с `%20` сайт отвечает 403.
        val path = tags.joinToString("") { "n.m.tags=${URLEncoder.encode(it, "UTF-8")}/" }
        return "$baseUrl/f/${path}page/$page/"
    }

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.selectFirst("a.cover")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { manga.setUrlWithoutDomain(it) }
        manga.title = cardTitle(element)
        manga.thumbnail_url = element.selectFirst("img")?.lazyImageUrl()
        return manga
    }

    private fun cardTitle(element: Element): String = element.selectFirst("div.caption")?.text()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()

    private fun parseDetails(document: Document): SManga {
        val details = SManga.create()
        details.title = document.selectFirst("#info-block h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.title().substringBefore(" »").trim()
        details.thumbnail_url = document.selectFirst("#cover img")?.lazyImageUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attributeUrl("content")

        val tagContainers = document.select("section#tags .tag-container")
        details.genre = tagContainers
            .filter { it.fieldLabel() == "Теги" }
            .flatMap { it.select("a.tag") }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
        details.author = tagContainers
            .firstOrNull { it.fieldLabel() == "Автор" }
            ?.select("a.tag")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }
        details.description = null
        details.status = SManga.UNKNOWN
        return details
    }

    private fun chapterDate(document: Document): Long = document.selectFirst("#info-block time[datetime]")
        ?.attr("datetime")
        ?.trim()
        ?.let { DATE_FORMAT.tryParseDateTime(it) }
        ?: 0L

    private fun splitChapterTitle(title: String): Pair<String?, Float?> {
        val cleaned = title.trimTrailingParentheses()
        val marker = findChapterMarker(cleaned) ?: return null to null
        val number = marker.groupValues[1].toFloatOrNull() ?: return null to null
        val base = cleaned.substring(0, marker.range.first)
            .trim()
            .trim(*BASE_SEPARATORS)
            .trim()
        if (base.isEmpty()) return null to null
        return base to number
    }

    private fun findChapterMarker(title: String): MatchResult? {
        val russian = CHAPTER_MARKER_RU.find(title)
        val english = CHAPTER_MARKER_EN.find(title)
        return when {
            russian == null -> english
            english == null -> russian
            english.range.first < russian.range.first -> english
            else -> russian
        }
    }

    private fun String.trimTrailingParentheses(): String {
        var result = trim()
        while (true) {
            val next = result.replace(TRAILING_PARENTHESES, "").trim()
            if (next == result) return result
            result = next
        }
    }

    private fun singleChapter(mangaUrl: String, title: String, date: Long): SChapter {
        val chapter = chapterFromTitle(title, 1F)
        chapter.setUrlWithoutDomain(mangaUrl)
        chapter.date_upload = date
        return chapter
    }

    private suspend fun groupedChapters(
        mangaUrl: String,
        title: String,
        base: String,
        number: Float,
        date: Long,
    ): List<SChapter> {
        val current = chapterFromTitle(title, number, chapterLabel(title))
        current.setUrlWithoutDomain(mangaUrl)
        // Дату ставим только текущей записи: за датами остальных пришлось бы идти
        // в каждую карточку, а это N лишних запросов.
        current.date_upload = date

        val found = searchSeriesChapters(base).filter { it.url != current.url }
        return (listOf(current) + found)
            .distinctBy { it.url }
            .sortedBy { it.chapter_number }
    }

    private suspend fun searchSeriesChapters(base: String): List<SChapter> {
        val normalizedBase = normalizeBase(base)
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var document = fetchDocument(searchUrl(page, base))
        while (true) {
            document.select(CARD_SELECTOR).forEach { element ->
                val caption = cardTitle(element)
                val (cardBase, cardNumber) = splitChapterTitle(caption)
                if (cardBase == null || cardNumber == null || normalizeBase(cardBase) != normalizedBase) {
                    return@forEach
                }
                val href = element.selectFirst("a.cover")?.absUrl("href")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("a")?.absUrl("href")?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val chapter = chapterFromTitle(caption, cardNumber, chapterLabel(caption))
                chapter.setUrlWithoutDomain(href)
                chapters += chapter
            }
            if (page >= CHAPTER_SEARCH_MAX_PAGES || document.selectFirst(SEARCH_NEXT) == null) break
            page++
            document = fetchDocument(searchUrl(page, base))
        }
        return chapters
    }

    private fun normalizeBase(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")
        .trim(' ', '.', ',', ':', ';', '-')

    private fun chapterFromTitle(title: String, number: Float, name: String = title): SChapter {
        val chapter = SChapter.create()
        chapter.name = name.ifBlank { "Chapter" }
        chapter.chapter_number = number
        return chapter
    }

    /**
     * Короткое имя главы для списка: «глава 4» вместо полного названия записи.
     * Suwayomi срезает из имени название манги, поэтому у текущей записи полное имя
     * превращалось в пустую строку, а у остальных частей оставалось длинным.
     */
    private fun chapterLabel(title: String): String {
        val cleaned = title.trimTrailingParentheses()
        val marker = findChapterMarker(cleaned) ?: return title
        return cleaned.substring(marker.range.first).trim().trim(*BASE_SEPARATORS).trim()
            .ifEmpty { title }
    }

    private suspend fun readerPages(url: String, document: Document): List<Page> {
        val count = PAGE_COUNT.find(document.text())?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()
        val pages = mutableListOf<Page>()
        for (pageNumber in 1..minOf(count, MAX_READER_PAGES)) {
            val imageUrl = try {
                fetchDocument("$url/$pageNumber/").selectFirst("#image-container img")?.attributeUrl("src")
            } catch (e: IOException) {
                null
            }
            if (imageUrl != null) pages += Page(pages.size, imageUrl = imageUrl)
        }
        return pages
    }

    private fun Element.attributeUrl(name: String): String? {
        val raw = attr(name)
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return absUrl(name).ifBlank { raw }
    }

    // Ленивые картинки: реальный адрес лежит в data-src, а src бывает data:-заглушкой.
    private fun Element.lazyImageUrl(): String? = attributeUrl("data-src") ?: attributeUrl("src")

    private fun Element.fieldLabel(): String = ownText().replace('\u00A0', ' ').trim()

    private fun networkFailureText(error: IOException): String {
        val host = URL(baseUrl).host
        return when (error) {
            is UnknownHostException -> "Не удалось найти адрес $host — проверьте подключение к интернету"
            is SocketTimeoutException -> "Сайт $host не ответил вовремя — попробуйте позже"
            is SocketException -> "Соединение с $host сброшено: похоже, сайт недоступен или блокируется"
            else -> "Сайт $host не отвечает: ${error.message ?: "сетевая ошибка"}"
        }
    }

    private fun String.isMysqlError() = contains("MySQL Error") || contains("Error Number: 1114")

    private fun String.isBrokenPage() = length < BROKEN_PAGE_MAX_LENGTH || isMysqlError()

    companion object {
        private const val CARD_SELECTOR = "#dle-content div.gallery"
        private const val NEXT_PAGE = "section.pagination a.next"
        private const val SEARCH_NEXT = "a#nextlink"

        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 700L
        private const val IMAGE_HOST_SUFFIX = "imgschan.xyz"
        private const val SITE_HOST_SUFFIX = "multi-manga.com"
        private const val BROKEN_PAGE_MAX_LENGTH = 2000
        private const val CHAPTER_SEARCH_MAX_PAGES = 3
        private const val MAX_READER_PAGES = 300

        private val BASE_SEPARATORS = " -–—:|".toCharArray()

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val CHAPTER_MARKER_RU =
            """(?:глава|часть|chapter|part|page|страница|stage)\s*(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
        private val CHAPTER_MARKER_EN = """(\d+)(?:st|nd|rd|th)?\s*(?:page|part|chapter)""".toRegex(RegexOption.IGNORE_CASE)
        private val TRAILING_PARENTHESES = """\s*\([^)]*\)\s*$""".toRegex()
        private val WHITESPACE = """\s+""".toRegex()
        private val PAGE_COUNT = """(\d+)\s*страниц""".toRegex()
    }
}

/** Сайт недоступен целиком: сеть, DNS, таймаут или сброс соединения. */
class SiteUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)
