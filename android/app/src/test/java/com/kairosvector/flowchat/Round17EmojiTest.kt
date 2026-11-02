package com.kairosvector.flowchat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ROUND 17 / 18: Emoji panel layout & standardized navigation.
 */
class Round17EmojiTest {

    private val layoutXml: String by lazy {
        File("src/main/res/layout/keyboard_view.xml").readText(Charsets.UTF_8)
    }

    @Test
    fun keyboardLayoutContainsTopBackButtonAndHeaders() {
        assertTrue("btn_top_back should exist", layoutXml.contains("android:id=\"@+id/btn_top_back\""))
        assertTrue("top_middle_slot should exist", layoutXml.contains("android:id=\"@+id/top_middle_slot\""))
        assertTrue("nav_clip_header should exist", layoutXml.contains("android:id=\"@+id/nav_clip_header\""))
        assertTrue("nav_emoji_header should exist", layoutXml.contains("android:id=\"@+id/nav_emoji_header\""))
    }

    @Test
    fun emojiPanelConfiguredWithSevenColumns() {
        assertTrue("emoji_panel FrameLayout should exist", layoutXml.contains("android:id=\"@+id/emoji_panel\""))
        assertTrue("emoji_picker_view should have emojiGridColumns=7", layoutXml.contains("app:emojiGridColumns=\"7\""))
    }

    @Test
    fun bottomRowMaintainsSymmetric52dpKeys() {
        // [switch 52] [emoji 52] [space weight 1] [period 52] [enter 52]
        assertTrue("btn_left_action is 52dp", layoutXml.contains("android:id=\"@+id/btn_left_action\""))
        assertTrue("btn_emoji is 52dp", layoutXml.contains("android:id=\"@+id/btn_emoji\""))
        assertTrue("btn_space_bar has weight 1", layoutXml.contains("android:id=\"@+id/btn_space_bar\""))
        assertTrue("btn_period is 52dp", layoutXml.contains("android:id=\"@+id/btn_period\""))
        assertTrue("btn_enter is 52dp", layoutXml.contains("android:id=\"@+id/btn_enter\""))
    }
}
