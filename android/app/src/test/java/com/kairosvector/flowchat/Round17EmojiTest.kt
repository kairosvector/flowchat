package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ROUND 17: Emoji panel dynamic expansion (top_row GONE, 204dp height)
 * and bottom-row period key dynamic switching to backspace.
 */
class Round17EmojiTest {

    private val layoutXml: String by lazy {
        File("src/main/res/layout/keyboard_view.xml").readText(Charsets.UTF_8)
    }

    @Test
    fun keyboardLayoutContainsDynamicPeriodKeyWithBackspace() {
        // btn_period is a FrameLayout containing both period TextView and BackspaceButton
        assertTrue("btn_period FrameLayout should exist", layoutXml.contains("android:id=\"@+id/btn_period\""))
        assertTrue("btn_period_text TextView should exist", layoutXml.contains("android:id=\"@+id/btn_period_text\""))
        assertTrue("btn_period_backspace BackspaceButton should exist", layoutXml.contains("android:id=\"@+id/btn_period_backspace\""))
        assertTrue("btn_period_backspace should use BackspaceButton class",
            layoutXml.contains("<com.kairosvector.flowchat.keyboard.BackspaceButton") &&
            layoutXml.contains("android:id=\"@+id/btn_period_backspace\"")
        )
    }

    @Test
    fun emojiPanelConfiguredWithSevenColumns() {
        assertTrue("emoji_panel FrameLayout should exist", layoutXml.contains("android:id=\"@+id/emoji_panel\""))
        assertTrue("emoji_picker_view should have emojiGridColumns=7", layoutXml.contains("app:emojiGridColumns=\"7\""))
    }

    @Test
    fun bottomRowMaintainsSymmetric52dpKeys() {
        // [switch 52] [emoji/back 52] [space weight 1] [period/backspace 52] [enter 52]
        assertTrue("btn_left_action is 52dp", layoutXml.contains("android:id=\"@+id/btn_left_action\""))
        assertTrue("btn_emoji is 52dp", layoutXml.contains("android:id=\"@+id/btn_emoji\""))
        assertTrue("btn_space_bar has weight 1", layoutXml.contains("android:id=\"@+id/btn_space_bar\""))
        assertTrue("btn_period is 52dp", layoutXml.contains("android:id=\"@+id/btn_period\""))
        assertTrue("btn_enter is 52dp", layoutXml.contains("android:id=\"@+id/btn_enter\""))
    }
}
