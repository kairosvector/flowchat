package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 6: only the last 4 characters of an API key are visible (fake strings only). */
class ApiKeyMaskTest {
    private val bullet = ApiKeyMask.BULLET.toString()

    @Test
    fun emptyKeyStaysEmpty() {
        assertEquals("", ApiKeyMask.mask(""))
    }

    @Test
    fun threeCharactersAreHiddenCompletely() {
        assertEquals(bullet.repeat(3), ApiKeyMask.mask("abc"))
    }

    @Test
    fun fourCharactersAreHiddenCompletely() {
        // showing the "last 4" of a 4-character key would show all of it
        assertEquals(bullet.repeat(4), ApiKeyMask.mask("abcd"))
    }

    @Test
    fun fiveCharactersShowOnlyTheLastFour() {
        assertEquals(bullet + "bcde", ApiKeyMask.mask("abcde"))
    }

    @Test
    fun aLongKeyShowsOnlyTheLastFour() {
        val key = "gsk_test_" + "x".repeat(27) + "3f9a" // 40 characters, fake
        assertEquals(40, key.length)
        val masked = ApiKeyMask.mask(key)
        assertEquals(40, masked.length)
        assertEquals(bullet.repeat(36) + "3f9a", masked)
        assertFalse(masked.contains("gsk"))
    }

    @Test
    fun visibleCountRules() {
        assertEquals(0, ApiKeyMask.visibleCount(0))
        assertEquals(0, ApiKeyMask.visibleCount(3))
        assertEquals(0, ApiKeyMask.visibleCount(4))
        assertEquals(4, ApiKeyMask.visibleCount(5))
        assertEquals(4, ApiKeyMask.visibleCount(40))
    }

    @Test
    fun theMaskKeepsTheLength() {
        for (n in listOf(0, 3, 4, 5, 40)) {
            assertEquals(n, ApiKeyMask.mask("k".repeat(n)).length)
        }
    }
}
