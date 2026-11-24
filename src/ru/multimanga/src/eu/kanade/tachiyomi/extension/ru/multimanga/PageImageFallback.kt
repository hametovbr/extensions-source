package eu.kanade.tachiyomi.extension.ru.multimanga

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Страница, которую сайт больше не отдаёт, не должна обрывать загрузку всей главы: вместо неё
 * клиент получает это изображение. Файл лежит в исходниках расширения и отдаётся через CDN,
 * потому что клиент скачивает картинку по обычному URL.
 */
internal const val PAGE_UNAVAILABLE_URL =
    "https://cdn.jsdelivr.net/gh/hametovbr/extensions-source@main/assets/page-unavailable.png"

/** Метка запросов картинок: по ней интерсептор отличает их от запросов HTML-страниц. */
internal object PageImageMarker

/**
 * Сколько сетевых сбоев подряд считаем признаком полностью недоступного CDN, а не
 * отдельными битыми страницами. Порог намеренно высокий: в галереях сайта встречаются
 * серии из нескольких отсутствующих файлов подряд, и глава из-за них рваться не должна.
 */
private const val NETWORK_FAILURE_LIMIT = 10

/**
 * Сколько держим хост из редиректа «мёртвым». Если CDN оживёт, через это время запрос
 * снова пройдёт обычным путём; успешный ответ снимает пометку раньше.
 */
private const val DEAD_REDIRECT_TTL_MS = 3 * 60_000L

/**
 * CDN отдаёт 301 на другой хост, и если тот висит до таймаута (30 с), каждая битая
 * страница стоит десятков секунд. Первый такой таймаут запоминает хост-цель как мёртвый,
 * и дальше редиректы на него обрываются сразу, без ожидания.
 */
internal class DeadRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Успешный ответ с хоста снимает с него пометку «мёртвый».
        if (response.isSuccessful) DeadHostRegistry.markAlive(request.url.host)

        if (response.code in 300..399) {
            val targetHost = response.header("Location")?.toHttpUrlOrNull()?.host
            if (targetHost != null) {
                RedirectTrace.targetHost = targetHost
                if (request.tag(PageImageMarker::class.java) != null && DeadHostRegistry.isDead(targetHost)) {
                    response.close()
                    throw RedirectToDeadHostException(targetHost)
                }
            }
        }
        return response
    }
}

internal class RedirectToDeadHostException(host: String) : IOException("Редирект на недоступный хост $host")

/**
 * Куда вёл редирект в рамках текущего вызова. Network-интерсептор и интерсептор заглушки
 * работают на одном потоке в рамках вызова, поэтому ThreadLocal достаточно.
 */
private object RedirectTrace {
    private val host = ThreadLocal<String?>()

    var targetHost: String?
        get() = host.get()
        set(value) {
            host.set(value)
        }

    fun reset() = host.remove()

    fun clear() = host.remove()
}

/**
 * Хосты, на которые уже был таймаут после редиректа. Список не захардкожен: он
 * наполняется по факту сбоя и истекает через [DEAD_REDIRECT_TTL_MS], чтобы оживший
 * CDN снова начал использоваться.
 */
private object DeadHostRegistry {
    private val deadHosts = ConcurrentHashMap<String, Long>()

    fun markDead(host: String) {
        deadHosts[host] = System.currentTimeMillis() + DEAD_REDIRECT_TTL_MS
    }

    fun isDead(host: String): Boolean {
        val until = deadHosts[host] ?: return false
        if (until > System.currentTimeMillis()) return true
        deadHosts.remove(host)
        return false
    }

    fun markAlive(host: String) {
        deadHosts.remove(host)
    }
}

internal class PageImageFallbackInterceptor : Interceptor {
    private val consecutiveNetworkFailures = AtomicInteger(0)

    @Volatile
    private var cachedPlaceholder: ByteArray? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(PageImageMarker::class.java) == null) return chain.proceed(request)

        RedirectTrace.reset()
        val response = try {
            // Одна попытка: повтор только удваивал ожидание на мёртвых страницах,
            // а заглушка и так сохраняет главу целой.
            chain.proceed(request)
        } catch (e: RedirectToDeadHostException) {
            // Хост уже известен как мёртвый — не ждём таймаут и не считаем это сбоем CDN.
            return placeholderResponse(chain, request)
        } catch (e: IOException) {
            // Если вызов умер уже после редиректа на другой хост, запоминаем его.
            RedirectTrace.targetHost?.let { DeadHostRegistry.markDead(it) }
            if (consecutiveNetworkFailures.incrementAndGet() >= NETWORK_FAILURE_LIMIT) {
                throw SiteUnavailableException("CDN изображений недоступен — попробуйте позже")
            }
            return placeholderResponse(chain, request)
        } finally {
            RedirectTrace.clear()
        }

        // Любой пришедший HTTP-ответ означает, что CDN жив: сбрасываем счётчик сетевых сбоев.
        consecutiveNetworkFailures.set(0)
        if (!response.needsPlaceholder()) return response

        val placeholder = try {
            placeholderResponse(chain, request)
        } catch (e: IOException) {
            // Заглушка тоже не пришла — отдаём исходный ответ, чтобы клиент показал причину.
            return response
        }
        response.close()
        return placeholder
    }

    private fun placeholderResponse(chain: Interceptor.Chain, request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "image/png")
        .body(placeholderBytes(chain).toResponseBody("image/png".toMediaType()))
        .build()

    private fun placeholderBytes(chain: Interceptor.Chain): ByteArray {
        cachedPlaceholder?.let { return it }
        synchronized(this) {
            cachedPlaceholder?.let { return it }

            val response = chain.proceed(
                Request.Builder()
                    .url(PAGE_UNAVAILABLE_URL)
                    .header("Accept", "image/*")
                    .build(),
            )
            val bytes = response.use { if (it.isSuccessful) it.body.bytes() else null }
            if (bytes == null || bytes.isEmpty()) {
                throw IOException("Заглушка недоступна: HTTP ${response.code}")
            }
            cachedPlaceholder = bytes
            return bytes
        }
    }
}

private fun Response.needsPlaceholder(): Boolean = when {
    // Это не «страницы нет»: нужен вход или сайт просит сбавить темп.
    code == 401 || code == 403 || code == 429 -> false
    isSuccessful -> !isImage()
    else -> true
}

private fun Response.isImage(): Boolean {
    if (!isSuccessful || body.contentLength() == 0L) return false
    val type = body.contentType() ?: return false
    return type.type == "image" || (type.type == "application" && type.subtype == "octet-stream")
}
