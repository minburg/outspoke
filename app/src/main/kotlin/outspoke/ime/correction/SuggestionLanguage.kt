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
    BULGARIAN("bg", "Bulgarian"),
    CROATIAN("hr", "Croatian"),
    CZECH("cs", "Czech"),
    DANISH("da", "Danish"),
    DUTCH("nl", "Dutch"),
    ENGLISH("en", "English"),
    ESTONIAN("et", "Estonian"),
    FINNISH("fi", "Finnish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    GREEK("el", "Greek"),
    HUNGARIAN("hu", "Hungarian"),
    ITALIAN("it", "Italian"),
    LATVIAN("lv", "Latvian"),
    LITHUANIAN("lt", "Lithuanian"),
    MALTESE("mt", "Maltese"),
    POLISH("pl", "Polish"),
    PORTUGUESE("pt", "Portuguese"),
    ROMANIAN("ro", "Romanian"),
    RUSSIAN("ru", "Russian"),
    SLOVAK("sk", "Slovak"),
    SLOVENIAN("sl", "Slovenian"),
    SPANISH("es", "Spanish"),
    SWEDISH("sv", "Swedish"),
    UKRAINIAN("uk", "Ukrainian"),
    ;

    companion object {
        /** All tags that are accepted as values in [AppPreferences.suggestionBarLanguages]. */
        val TAG_SET: Set<String> = entries.map { it.tag }.toSet()

        fun fromTag(tag: String): SuggestionLanguage? = entries.firstOrNull { it.tag == tag }
    }
}
