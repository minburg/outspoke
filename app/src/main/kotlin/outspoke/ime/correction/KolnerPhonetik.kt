package dev.brgr.outspoke.ime.correction

/**
 * Kölner Phonetik (Cologne Phonetics) — a phonetic algorithm for German that maps words
 * to digit codes representing their sound.  Similar to Soundex but tuned for German
 * phonology including umlauts and letter combinations like "sch", "ck", "pf".
 *
 * Reference: H.J. Postel, "Die Kölner Phonetik", IBM-Nachrichten 19 (1969), pp. 925-931.
 */
object KolnerPhonetik {

    fun encode(input: String): String {
        return try {
            encodeInternal(input)
        } catch (e: Exception) {
            ""
        }
    }

    private fun encodeInternal(input: String): String {
        val s = input.lowercase()
            .replace('ä', 'a')
            .replace('ö', 'o')
            .replace('ü', 'u')
            .replace('ß', 's')
            .replace('é', 'e')
            .replace('è', 'e')
            .replace('à', 'a')

        if (s.isEmpty()) return ""

        val digits = StringBuilder()
        var prev = '0'

        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else '\u0000'
            val next2 = if (i + 2 < s.length) s[i + 2] else '\u0000'

            val code = when {
                c == 'a' || c == 'e' || c == 'i' || c == 'j' ||
                        c == 'o' || c == 'u' || c == 'y' -> if (i == 0) "0" else ""

                c == 'b' -> "1"
                c == 'p' && next != 'h' -> "1"
                c == 'p' && next == 'h' -> {
                    i++; "3"
                }

                c == 'd' || c == 't' -> {
                    if (next == 'c' || next == 's' || next == 'z') "8"
                    else "2"
                }

                c == 'f' || c == 'v' || c == 'w' -> "3"

                c == 'g' || c == 'k' || c == 'q' -> "4"
                c == 'c' -> {
                    // 'c' at start before a, h, k, l, o, q, r, u, x = 4; else 8
                    // after s or z = 8
                    val beforeVoiced = next == 'a' || next == 'h' || next == 'k' ||
                            next == 'l' || next == 'o' || next == 'q' ||
                            next == 'r' || next == 'u' || next == 'x'
                    if (i == 0 && beforeVoiced) "4"
                    else if (prev == '8') "8"
                    else if (beforeVoiced) "4"
                    else "8"
                }

                c == 'x' -> if (prev == '4') "8" else "48"

                c == 'l' -> "5"
                c == 'm' || c == 'n' -> "6"
                c == 'r' -> "7"

                c == 's' || c == 'z' -> "8"

                // sch → 8, otherwise skip
                c == 's' && next == 'c' && next2 == 'h' -> {
                    i += 2; "8"
                }

                else -> ""
            }

            for (d in code) {
                val dChar = d
                if (dChar != '0' || digits.isEmpty()) {
                    if (dChar != prev || digits.isEmpty()) {
                        digits.append(dChar)
                        prev = dChar
                    }
                } else {
                    // leading 0: only add if very first
                    if (digits.isEmpty()) {
                        digits.append(dChar)
                        prev = dChar
                    }
                }
            }
            i++
        }

        // Remove consecutive duplicate digits (except first char)
        return dedupe(digits.toString())
    }

    private fun dedupe(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder()
        sb.append(s[0])
        for (i in 1 until s.length) {
            if (s[i] != s[i - 1]) sb.append(s[i])
        }
        return sb.toString()
    }
}
