package dev.brgr.outspoke.ime.correction

/**
 * Double Metaphone — a phonetic algorithm for English (and many European languages) that
 * produces a primary and optional alternate phonetic code for a word.
 *
 * This is a clean-room Kotlin implementation of Lawrence Philips' Double Metaphone
 * algorithm (2000).  Only the primary code is used for indexing; the alternate code
 * provides a fallback when the primary code bucket is small.
 *
 * Reference: Lawrence Philips, "The Double Metaphone Search Algorithm",
 * C/C++ Users Journal, June 2000.
 */
object DoubleMetaphone {

    data class Codes(val primary: String, val alternate: String)

    fun encode(input: String): Codes {
        return try {
            encodeInternal(input)
        } catch (e: Exception) {
            Codes("", "")
        }
    }

    private fun encodeInternal(input: String): Codes {
        if (input.isEmpty()) return Codes("", "")

        val s = input.uppercase()
            .replace('À', 'A').replace('Â', 'A').replace('Ä', 'A')
            .replace('Ç', 'S')
            .replace('É', 'E').replace('È', 'E').replace('Ê', 'E').replace('Ë', 'E')
            .replace('Î', 'I').replace('Ï', 'I')
            .replace('Ô', 'O').replace('Ö', 'O')
            .replace('Ù', 'U').replace('Û', 'U').replace('Ü', 'U')
            .replace('Ÿ', 'Y')
            .replace('Ñ', 'N')

        val primary = StringBuilder()
        val alternate = StringBuilder()

        fun add(p: String, a: String = p) {
            primary.append(p)
            alternate.append(a)
        }

        fun charAt(i: Int) = if (i in s.indices) s[i] else '\u0000'
        fun substring(start: Int, end: Int): String {
            val s2 = start.coerceAtLeast(0)
            val e2 = end.coerceAtMost(s.length)
            return if (s2 >= e2) "" else s.substring(s2, e2)
        }

        var pos = 0

        // Skip initial silent letters: AE, GN, KN, PN, WR
        if (substring(0, 2) in setOf("AE", "GN", "KN", "PN", "WR")) pos = 1

        // Initial vowel → A
        if (s[0].isVowel()) {
            add("A")
            pos = 1
        }

        while (pos < s.length && primary.length < 4) {
            val c = charAt(pos)

            when (c) {
                'A', 'E', 'I', 'O', 'U', 'Y' -> {
                    if (pos == 0) add("A")
                    pos++
                }

                'B' -> {
                    add("P")
                    pos += if (charAt(pos + 1) == 'B') 2 else 1
                }

                'Ç' -> {
                    add("S"); pos++
                }

                'C' -> {
                    when {
                        pos > 1 && !charAt(pos - 2).isVowel() && substring(pos - 1, pos + 3) == "ACH" &&
                                charAt(pos + 2) != 'I' && (charAt(pos + 2) != 'E' || substring(
                            pos - 2,
                            pos + 4
                        ) in setOf("BACHER", "MACHER")) -> {
                            add("K"); pos += 2
                        }

                        pos == 0 && substring(0, 6) == "CAESAR" -> {
                            add("S"); pos += 2
                        }

                        substring(pos, pos + 4) == "CHIA" -> {
                            add("K"); pos += 2
                        }

                        substring(pos, pos + 2) == "CH" -> {
                            when {
                                pos == 0 && (substring(0, 5) in setOf("CHAE")) -> {
                                    add("K", "X"); pos += 2
                                }

                                (substring(pos - 2, pos) in setOf("MN", "AE", "OE")) ||
                                        (pos > 0 && substring(pos - 1, pos + 4) == "ORCHES") ||
                                        charAt(pos + 2) in setOf('T', 'S') ||
                                        (charAt(pos - 1) in setOf('A', 'O', 'U', 'E') && charAt(pos + 2) in setOf(
                                            'L',
                                            'R',
                                            'N',
                                            'M',
                                            'B',
                                            'H',
                                            'F',
                                            'V',
                                            'W'
                                        )) -> {
                                    add("K"); pos += 2
                                }

                                pos > 0 -> {
                                    add("X"); pos += 2
                                }

                                else -> {
                                    add("K"); pos += 2
                                }
                            }
                        }

                        substring(pos, pos + 2) == "CZ" && substring(pos - 2, pos) != "WI" -> {
                            add("S", "X"); pos += 2
                        }

                        substring(pos + 1, pos + 4) == "CIA" -> {
                            add("X"); pos += 3
                        }

                        substring(pos, pos + 2) in setOf("CC") && !(pos == 1 && s[0] == 'M') -> {
                            if (charAt(pos + 2) in setOf('I', 'E', 'H')) {
                                if (substring(pos, pos + 3) == "CCH") {
                                    add("K"); pos += 3
                                } else {
                                    add("KS"); pos += 2
                                }
                            } else {
                                add("K"); pos += 2
                            }
                        }

                        substring(pos, pos + 2) in setOf("CK", "CG", "CQ") -> {
                            add("K"); pos += 2
                        }

                        substring(pos, pos + 2) in setOf("CI", "CE", "CY") -> {
                            add(if (substring(pos, pos + 3) in setOf("CIO", "CIE", "CIA")) "X" else "S", "X")
                            pos += 2
                        }

                        else -> {
                            add("K"); pos += if (substring(pos + 1, pos + 3) in setOf(" C", " Q", " G")) 3 else 1
                        }
                    }
                }

                'D' -> {
                    if (substring(pos, pos + 2) == "DG" && charAt(pos + 2) in setOf('I', 'E', 'Y')) {
                        add("J"); pos += 3
                    } else if (substring(pos, pos + 2) in setOf("DT", "DD")) {
                        add("T"); pos += 2
                    } else {
                        add("T"); pos++
                    }
                }

                'F' -> {
                    add("F"); pos += if (charAt(pos + 1) == 'F') 2 else 1
                }

                'G' -> {
                    when {
                        charAt(pos + 1) == 'H' -> {
                            if (pos > 0 && !charAt(pos - 1).isVowel()) {
                                add("K"); pos += 2
                            } else if (pos == 0 && charAt(pos + 2) != 'I' && !charAt(pos + 3).isVowel()) {
                                add("K"); pos += 2
                            } else if (pos == 0) {
                                add("J"); pos += 2
                            } else if ((pos > 1 && charAt(pos - 2) in setOf('B', 'H', 'D')) ||
                                (pos > 2 && charAt(pos - 3) in setOf('B', 'H', 'D')) ||
                                (pos > 3 && charAt(pos - 4) in setOf('B', 'H'))
                            ) {
                                pos += 2
                            } else {
                                if (pos > 2 && charAt(pos - 1) == 'U' && charAt(pos - 3) in setOf(
                                        'C',
                                        'G',
                                        'L',
                                        'R',
                                        'T'
                                    )
                                ) add("F")
                                else if (pos > 0 && charAt(pos - 1) != 'I') add("K")
                                pos += 2
                            }
                        }

                        charAt(pos + 1) == 'N' -> {
                            if (pos == 1 && s[0].isVowel()) add("KN", "N")
                            else if (substring(pos + 2, pos + 4) != "EY" && charAt(pos + 1) != 'Y') add("N", "KN")
                            pos += 2
                        }

                        substring(pos + 1, pos + 3) == "LI" && pos == 0 -> {
                            add("KL", "L"); pos += 2
                        }

                        pos == 0 && charAt(pos + 1) in setOf('Y') -> {
                            add("K", "J"); pos += 2
                        }

                        (substring(pos + 1, pos + 3) in setOf(
                            "ES",
                            "EP",
                            "EB",
                            "EL",
                            "EY",
                            "IB",
                            "IL",
                            "IN",
                            "IE",
                            "EI",
                            "ER"
                        )) ||
                                (charAt(pos + 1) == 'Y') -> {
                            add("K", "J"); pos += 2
                        }

                        charAt(pos + 1) == 'G' -> {
                            add("K"); pos += 2
                        }

                        else -> {
                            add("K"); pos++
                        }
                    }
                }

                'H' -> {
                    if ((pos == 0 || charAt(pos - 1).isVowel()) && charAt(pos + 1).isVowel()) {
                        add("H"); pos++
                    } else pos++
                }

                'J' -> {
                    if (substring(pos, pos + 4) == "JOSE" || substring(0, 4) == "SAN ") {
                        add("H")
                    } else add("J", if (pos == 0 && substring(pos, pos + 4) == "JOSE") "H" else "J")
                    pos += if (charAt(pos + 1) == 'J') 2 else 1
                }

                'K' -> {
                    add("K"); pos += if (charAt(pos + 1) == 'K') 2 else 1
                }

                'L' -> {
                    add("L")
                    pos += if (charAt(pos + 1) == 'L') {
                        if (((pos == s.length - 3 && s.length >= 3 && charAt(pos - 1).isVowel() && charAt(pos + 2).isVowel()) ||
                                    substring(s.length - 2, s.length) in setOf("AS", "OS") ||
                                    charAt(pos - 1) in setOf('A', 'O')) && alternate.isNotEmpty()
                        ) alternate.setCharAt(alternate.length - 1, ' ')
                        2
                    } else 1
                }

                'M' -> {
                    add("M")
                    pos += if (substring(pos - 1, pos + 3) == "UMB" && (pos + 1 == s.length - 1 || substring(
                            pos + 2,
                            pos + 4
                        ) == "ER") ||
                        charAt(pos + 1) == 'M'
                    ) 2 else 1
                }

                'N' -> {
                    add("N"); pos += if (charAt(pos + 1) == 'N') 2 else 1
                }

                'Ñ' -> {
                    add("N"); pos++
                }

                'P' -> {
                    if (charAt(pos + 1) == 'H') {
                        add("F"); pos += 2
                    } else {
                        add("P"); pos += if (charAt(pos + 1) in setOf('P', 'B')) 2 else 1
                    }
                }

                'Q' -> {
                    add("K"); pos += if (charAt(pos + 1) == 'Q') 2 else 1
                }

                'R' -> {
                    if (pos == s.length - 1 && !charAt(pos - 1).isVowel() && substring(pos - 2, pos) != "ME") add(
                        "R",
                        ""
                    )
                    else add("R")
                    pos += if (charAt(pos + 1) == 'R') 2 else 1
                }

                'S' -> {
                    when {
                        substring(pos - 1, pos + 2) in setOf("ISL", "YSL") -> pos++
                        pos == 0 && substring(0, 5) == "SUGAR" -> {
                            add("X", "S"); pos++
                        }

                        substring(pos, pos + 2) == "SH" -> {
                            add("X"); pos += 2
                        }

                        substring(pos, pos + 3) in setOf("SIO", "SIA") -> {
                            add("S", "X"); pos += 3
                        }

                        pos == 0 && substring(1, 3) in setOf("M", "N", "L", "W") -> {
                            add("S", "X"); pos++
                        }

                        substring(pos, pos + 2) == "SC" -> {
                            when {
                                charAt(pos + 2) == 'H' -> {
                                    if (substring(pos + 3, pos + 5) in setOf("OO", "ER", "EN", "UY", "ED", "EM")) {
                                        add("SK"); pos += 3
                                    } else {
                                        add(if (pos == 0 && !charAt(3).isVowel() && charAt(3) != 'W') "X" else "X"); pos += 3
                                    }
                                }

                                charAt(pos + 2) in setOf('I', 'E', 'Y') -> {
                                    add("S"); pos += 3
                                }

                                else -> {
                                    add("SK"); pos += 3
                                }
                            }
                        }

                        pos == s.length - 1 && substring(pos - 2, pos) in setOf("AI", "OI") -> {
                            add("", "S"); pos++
                        }

                        else -> {
                            add("S"); pos += if (charAt(pos + 1) in setOf('S', 'Z')) 2 else 1
                        }
                    }
                }

                'T' -> {
                    when {
                        substring(pos, pos + 3) in setOf("TIA", "TCH") -> {
                            add("X"); pos += 3
                        }

                        substring(pos, pos + 2) in setOf("TH") || substring(pos, pos + 3) == "TTH" -> {
                            add("0", "T")
                            pos += if (charAt(pos + 1) == 'T' && charAt(pos + 2) == 'H') 3 else 2
                        }

                        substring(pos, pos + 2) in setOf("TT", "TD") -> {
                            add("T"); pos += 2
                        }

                        else -> {
                            add("T"); pos++
                        }
                    }
                }

                'V' -> {
                    add("F"); pos += if (charAt(pos + 1) == 'V') 2 else 1
                }

                'W' -> {
                    if (substring(pos, pos + 2) == "WR") {
                        add("R"); pos += 2
                    } else {
                        if (pos == 0 && (charAt(pos + 1).isVowel() || substring(0, 2) == "WH")) add("A")
                        if ((pos == s.length - 1 && charAt(pos - 1).isVowel()) ||
                            substring(pos - 1, pos + 5) in setOf("EWSKI", "EWSKY", "OWSKI", "OWSKY") ||
                            substring(0, 3) == "SCH"
                        ) {
                            add("", "F"); pos++
                        } else if (substring(pos, pos + 4) in setOf("WICZ", "WITZ")) {
                            add("TS", "FX"); pos += 4
                        } else pos++
                    }
                }

                'X' -> {
                    if (!(pos == s.length - 1 && (substring(pos - 3, pos) in setOf("IAU", "EAU") ||
                                substring(pos - 2, pos) in setOf("AU", "OU")))
                    ) add("KS")
                    pos += if (charAt(pos + 1) in setOf('C', 'X')) 2 else 1
                }

                'Z' -> {
                    if (charAt(pos + 1) == 'H') {
                        add("J"); pos += 2
                    } else {
                        if (substring(pos + 1, pos + 3) in setOf("ZO", "ZI", "ZA") || (charAt(pos - 1) != 'Z')) add(
                            "S",
                            "TS"
                        )
                        else add("S")
                        pos += if (charAt(pos + 1) == 'Z') 2 else 1
                    }
                }

                else -> pos++
            }
        }

        return Codes(
            primary.toString().trim().take(6),
            alternate.toString().replace(" ", "").take(6)
        )
    }

    private fun Char.isVowel() = this in "AEIOU"
}
