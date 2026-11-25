package eu.kanade.tachiyomi.extension.ru.multimanga

internal data class ChapterTitleParts(
    val base: String?,
    val number: Float?,
    val label: String?,
)

/**
 * Разбор названий записей DLE-сайта: «Серия - глава 4 (перевод)».
 * Общий набор фикстур и ожидаемых результатов описан в docs/extensions-alignment.md.
 */
internal object ChapterTitles {
    private val MARKER_RU = Regex(
        """(?:глава|часть|chapter|part|page|страница|stage)\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val MARKER_EN = Regex(
        """(\d+)(?:st|nd|rd|th)?\s*(?:page|part|chapter)""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_PARENTHESES = Regex("""\s*\([^)]*\)\s*$""")
    private val WHITESPACE = Regex("""\s+""")

    private val BASE_TRIM = charArrayOf(' ', '-', '–', '—', ':', '|')
    private val LABEL_TRIM = charArrayOf(' ', '-', '–', '—', ':', '|', '.', ',', ';')

    fun parse(title: String): ChapterTitleParts {
        val cleaned = title.trimTrailingParentheses()
        val marker = findMarker(cleaned) ?: return ChapterTitleParts(null, null, null)
        val number = marker.groupValues[1].toFloatOrNull() ?: return ChapterTitleParts(null, null, null)

        // База может быть пустой («- глава 2»): группировать нечего, но имя и номер
        // главы всё равно нужны одиночной записи.
        val base = cleaned.substring(0, marker.range.first).trim(*BASE_TRIM).ifEmpty { null }
        val label = cleaned.substring(marker.range.first).trim(*LABEL_TRIM).ifEmpty { null }
        return ChapterTitleParts(base, number, label)
    }

    fun normalizeBase(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")
        .trim(' ', '.', ',', ':', ';', '-')

    private fun findMarker(title: String): MatchResult? {
        val russian = MARKER_RU.find(title)
        val english = MARKER_EN.find(title)
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
}
