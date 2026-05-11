package dev.brgr.outspoke.ime.correction

/**
 * The set of languages supported by the on-device word-correction feature.
 *
 * Each entry carries:
 * - [tag]         BCP-47 language tag used as asset filename suffix (dict_<tag>.txt / lm_<tag>.arpa)
 * - [displayName] Human-readable name shown in the settings multi-select
 *
 * Entries are listed in alphabetical order by display name, as required by the settings UI.
 */
enum class SuggestionLanguage(val tag: String, val displayName: String) {
    DUTCH("nl", "Dutch"),
    ENGLISH("en", "English"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    POLISH("pl", "Polish"),
    SPANISH("es", "Spanish"),
    ;

    companion object {
        /** All tags that are accepted as values in [AppPreferences.suggestionBarLanguages]. */
        val TAG_SET: Set<String> = entries.map { it.tag }.toSet()

        fun fromTag(tag: String): SuggestionLanguage? = entries.firstOrNull { it.tag == tag }
    }
}
