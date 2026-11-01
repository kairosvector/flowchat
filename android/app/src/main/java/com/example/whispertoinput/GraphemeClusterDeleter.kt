package com.example.whispertoinput

/**
 * Determines grapheme cluster boundaries using [android.icu.text.BreakIterator]
 * with JVM fallback support for unit tests (Round 14 §C3).
 */
object GraphemeClusterDeleter {
    /**
     * Determines how many UTF-16 code units (Char) before the cursor should be deleted
     * to remove exactly one extended grapheme cluster (e.g. 👨‍👩‍👧, ❤️, 🇹🇼, or a simple character).
     */
    fun getDeleteCharCount(beforeCursor: CharSequence?): Int {
        if (beforeCursor.isNullOrEmpty()) return 1
        val text = beforeCursor.toString()
        try {
            val iter = android.icu.text.BreakIterator.getCharacterInstance()
            iter.setText(text)
            val end = iter.last()
            val start = iter.previous()
            if (start != android.icu.text.BreakIterator.DONE && end > start) {
                return end - start
            }
        } catch (_: Throwable) {
            // Fallback when android.icu is not mocked in JVM unit tests
        }
        return computeGraphemeClusterLength(text)
    }

    /**
     * Pure algorithm to calculate the length of the trailing grapheme cluster
     * for unit tests running on JVM.
     */
    fun computeGraphemeClusterLength(text: String): Int {
        if (text.isEmpty()) return 1
        val end = text.length
        var i = end
        var regionalIndicatorCount = 0

        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            val charCount = Character.charCount(cp)
            val currPos = i - charCount

            // Regional indicator symbols (flags, e.g. 🇹🇼: U+1F1E6..U+1F1FF)
            if (cp in 0x1F1E6..0x1F1FF) {
                regionalIndicatorCount++
                i = currPos
                if (regionalIndicatorCount == 2) {
                    return end - currPos
                }
                continue
            } else if (regionalIndicatorCount == 1) {
                return end - (currPos + charCount)
            }

            // Variation Selectors (e.g. VS16 U+FE0F for emoji representation like ❤️: U+2764 U+FE0F)
            if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) {
                i = currPos
                continue
            }

            // Skin tone modifiers (U+1F3FB..U+1F3FF)
            if (cp in 0x1F3FB..0x1F3FF) {
                i = currPos
                continue
            }

            // Combining characters / Keycap (U+20E3)
            if (cp == 0x20E3) {
                i = currPos
                continue
            }

            // Zero Width Joiner (U+200D)
            if (cp == 0x200D) {
                i = currPos
                continue
            }

            // If the character immediately preceding currPos is a ZWJ (U+200D),
            // this base character is part of a multi-character ZWJ emoji sequence
            if (currPos > 0 && Character.codePointBefore(text, currPos) == 0x200D) {
                i = currPos
                continue
            }

            // Base character reached without preceding ZWJ
            return end - currPos
        }

        return end
    }
}
