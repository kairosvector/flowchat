package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeClusterDeleterTest {

    @Test
    fun testComplexEmojiFamily() {
        val family = "👨\u200D👩\u200D👧"
        val count = GraphemeClusterDeleter.getDeleteCharCount(family)
        assertEquals(family.length, count)
        assertEquals(8, count)

        val prefixWithFamily = "Testing: $family"
        assertEquals(8, GraphemeClusterDeleter.getDeleteCharCount(prefixWithFamily))
    }

    @Test
    fun testHeartWithVariationSelector() {
        val heart = "❤️" // \u2764\uFE0F
        val count = GraphemeClusterDeleter.getDeleteCharCount(heart)
        assertEquals(2, count)

        val prefixWithHeart = "I love you ❤️"
        assertEquals(2, GraphemeClusterDeleter.getDeleteCharCount(prefixWithHeart))
    }

    @Test
    fun testFlagEmoji() {
        val flag = "🇹🇼" // \uD83C\uDDF9\uD83C\uDDFC
        val count = GraphemeClusterDeleter.getDeleteCharCount(flag)
        assertEquals(4, count)

        val prefixWithFlag = "Taiwan 🇹🇼"
        assertEquals(4, GraphemeClusterDeleter.getDeleteCharCount(prefixWithFlag))
    }

    @Test
    fun testSkinToneModifier() {
        val thumbsUp = "👍🏽" // \uD83D\uDC4D\uD83C\uDFFD
        val count = GraphemeClusterDeleter.getDeleteCharCount(thumbsUp)
        assertEquals(4, count)
    }

    @Test
    fun testStandardSurrogatePairEmoji() {
        val smile = "😊" // \uD83D\uDE0A
        val count = GraphemeClusterDeleter.getDeleteCharCount(smile)
        assertEquals(2, count)
    }

    @Test
    fun testRegularCharacters() {
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount("A"))
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount("Hello"))
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount("中"))
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount("文字編輯"))
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount(""))
        assertEquals(1, GraphemeClusterDeleter.getDeleteCharCount(null))
    }
}
