package eu.kanade.tachiyomi.extension.ru.multimanga

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
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

internal class PageImageFallbackInterceptor : Interceptor {
    private val consecutiveNetworkFailures = AtomicInteger(0)

    @Volatile
    private var cachedPlaceholder: ByteArray? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(PageImageMarker::class.java) == null) return chain.proceed(request)

        val response = try {
            requestImage(chain, request)
        } catch (e: IOException) {
            // Заглушка вместо обрыва: на сайте встречаются галереи, где несколько страниц
            // подряд лежат на мёртвом CDN, и глава должна скачаться целиком.
            if (consecutiveNetworkFailures.incrementAndGet() >= NETWORK_FAILURE_LIMIT) {
                throw SiteUnavailableException("CDN изображений недоступен — попробуйте позже")
            }
            return placeholderResponse(chain, request)
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

    /** Один повтор: единичный сброс или таймаут CDN ещё не повод ставить заглушку. */
    private fun requestImage(chain: Interceptor.Chain, request: Request): Response = try {
        chain.proceed(request)
    } catch (e: IOException) {
        chain.proceed(request)
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
