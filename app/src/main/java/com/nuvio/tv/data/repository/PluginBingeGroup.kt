package com.nuvio.tv.data.repository

/**
 * Plugin scrapers do not declare a Stremio `bingeGroup`, so the "play the next episode from
 * the same source" logic never matched their streams and always reopened the stream picker.
 *
 * This gives every plugin stream a stable group made of what the user actually chose:
 * provider, quality and audio language. The next episode auto-plays only when the same
 * provider offers that same combination again; otherwise the picker is shown as before.
 * Episode-specific text (titles, server names) is deliberately left out.
 */
internal object PluginBingeGroup {

    fun of(
        scraperId: String,
        quality: String?,
        language: String?,
        name: String?,
        title: String?
    ): String {
        val qualityToken = quality?.trim()?.lowercase().orEmpty().ifEmpty { "unknown" }
        return "plugin|$scraperId|$qualityToken|${languageToken(language, name, title)}"
    }

    private fun languageToken(language: String?, name: String?, title: String?): String {
        language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        val text = listOfNotNull(name, title).joinToString(" ")
        return when {
            SUB_REGEX.containsMatchIn(text) -> "sub"
            text.contains("🇮🇹") || ITA_REGEX.containsMatchIn(text) -> "it"
            text.contains("🇯🇵") || JPN_REGEX.containsMatchIn(text) -> "ja"
            text.contains("🇬🇧") || text.contains("🇺🇸") || ENG_REGEX.containsMatchIn(text) -> "en"
            LAT_REGEX.containsMatchIn(text) -> "es"
            else -> "any"
        }
    }

    private val SUB_REGEX = Regex("\\bsub(\\s|-)?ita\\b|\\bsubbed\\b|\\bsub\\b", RegexOption.IGNORE_CASE)
    private val ITA_REGEX = Regex("\\bita(lian[oa]?)?\\b", RegexOption.IGNORE_CASE)
    private val JPN_REGEX = Regex("\\b(jpn|jap|japanese)\\b", RegexOption.IGNORE_CASE)
    private val ENG_REGEX = Regex("\\b(eng|english)\\b", RegexOption.IGNORE_CASE)
    private val LAT_REGEX = Regex("\\b(lat|latino|castellano|español|espanol)\\b", RegexOption.IGNORE_CASE)
}
