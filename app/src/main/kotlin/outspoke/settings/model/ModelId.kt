package dev.brgr.outspoke.settings.model

import dev.brgr.outspoke.settings.model.ModelId.Companion.DEFAULT


/**
 * Type-safe identifier for every supported on-device speech recognition model.
 *
 * [storageDirName] is the name of the sub-directory under `<filesDir>/models/` where all
 * files for that model are stored. Changing it will break existing installs - treat as stable.
 */
enum class ModelId(val storageDirName: String) {
    PARAKEET_V3("parakeet-v3"),
    VOXTRAL_MINI("voxtral-mini-4b"),
    WHISPER_SMALL("whisper-small-int8"),
    ;

    companion object {
        /** The model used when no preference has been saved yet. */
        val DEFAULT: ModelId = PARAKEET_V3

        /** Returns the [ModelId] whose [storageDirName] matches [name], or [DEFAULT] if none found. */
        fun fromName(name: String): ModelId =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

