package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 5: the "at least 4 counted characters" polish rule. */
class PolishCharCountTest {
    private fun assertCount(expected: Int, text: String) =
        assertEquals("count of \"$text\"", expected, countPolishChars(text))

    @Test
    fun minimumIsFourAndIncluded() {
        assertEquals(4, POLISH_MIN_COUNTED_CHARS)
    }

    @Test
    fun shortAnswerIsSkipped() {
        assertCount(2, "好的。")
        assertFalse(shouldPolish("好的。"))
    }

    @Test
    fun threeCharactersAreSkipped() {
        assertCount(3, "約明天")
        assertFalse(shouldPolish("約明天"))
    }

    @Test
    fun fourCharactersArePolished() {
        assertCount(4, "約後天吧")
        assertTrue(shouldPolish("約後天吧"))
    }

    @Test
    fun selfCorrectionSentenceIsPolished() {
        // 10 characters once the comma is left out
        assertCount(10, "約後天，不然是明天才對")
        assertTrue(shouldPolish("約後天，不然是明天才對"))
        assertCount(10, "約後天不然是明天才對")
    }

    @Test
    fun onlyPunctuationAndSpacesCountsZero() {
        assertCount(0, "， 。 ！ ?")
        assertFalse(shouldPolish("， 。 ！ ?"))
        assertCount(0, "")
        assertCount(0, " \t\n　 ")
    }

    @Test
    fun latinWordsAndSpacesCountLetters() {
        // O, K, 好 -> 3
        assertCount(3, "OK 好")
        assertFalse(shouldPolish("OK 好"))
        assertCount(4, "OK 好嗎")
    }

    @Test
    fun digitsCount() {
        assertCount(4, "1,000")
        assertCount(4, "3.14 好")
    }

    @Test
    fun asciiAndFullWidthPunctuationAreIgnored() {
        assertCount(2, "(好)，[的]！!?；;：:「」『』《》…—～~")
        assertCount(1, "＋＄好")
    }

    @Test
    fun emojiCountsOncePerVisibleCharacter() {
        // each emoji is a surrogate pair (2 UTF-16 units) but one visible character
        assertCount(1, "😀")
        assertCount(4, "😀好嗎吧")
        assertCount(3, "😀😀😀")
        assertTrue(shouldPolish("😀😀好嗎"))
    }
}
