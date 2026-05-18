package dev.brgr.outspoke.ime.correction

import android.content.Context
import java.io.File

/**
 * Manages on-disk locations for downloadable word-correction data files.
 *
 * Each language stores two files:
 *   `<filesDir>/suggestion_files/<tag>/dict_<tag>.txt`
 *   `<filesDir>/suggestion_files/<tag>/lm_<tag>.arpa`
 *
 * All paths are within [Context.filesDir] — no external-storage permission is required.
 */
object SuggestionFileManager {

    private const val ROOT = "suggestion_files"

    /**
     * Base URL from which correction files are downloaded.
     * Pinned to a specific release tag so that existing installs are never
     * broken by future data updates. Bump this constant (and the release tag
     * in outspoke-data) whenever the file format or content changes materially.
     */
    const val BASE_URL = "https://github.com/minburg/outspoke-data/releases/download/v2"

    fun getLanguageDir(context: Context, tag: String): File =
        File(context.filesDir, "$ROOT/$tag")

    fun getDictFile(context: Context, tag: String): File =
        File(getLanguageDir(context, tag), "dict_$tag.txt")

    fun getLmFile(context: Context, tag: String): File =
        File(getLanguageDir(context, tag), "lm_$tag.arpa")

    /**
     * Returns `true` only when both the dictionary and language model files are present
     * and non-empty for [tag].
     */
    fun isLanguageReady(context: Context, tag: String): Boolean {
        val dict = getDictFile(context, tag)
        val lm = getLmFile(context, tag)
        return dict.exists() && dict.length() > 0 && lm.exists() && lm.length() > 0
    }

    /**
     * Deletes all downloaded files for [tag].
     */
    fun deleteLanguage(context: Context, tag: String) {
        getLanguageDir(context, tag).deleteRecursively()
    }
}
