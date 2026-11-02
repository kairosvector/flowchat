package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ROUND 18: Top navigation bar standardization and symmetric bottom row.
 */
class Round18NavTest {

    private val layoutXml: String by lazy {
        File("src/main/res/layout/keyboard_view.xml").readText(Charsets.UTF_8)
    }

    @Test
    fun testTopRowNavigationElements() {
        // [btn_top_back 48dp] [tab_voice] [tab_clip] [top_middle_slot] [btn_tone] [btn_backspace]
        assertTrue("btn_top_back exists with arrow icon",
            layoutXml.contains("android:id=\"@+id/btn_top_back\"") &&
            layoutXml.contains("android:src=\"@drawable/kb_ic_arrow_back\"")
        )
        assertTrue("top_middle_slot exists as weight 1 FrameLayout",
            layoutXml.contains("android:id=\"@+id/top_middle_slot\"")
        )
        assertTrue("nav_clip_header exists inside top_middle_slot",
            layoutXml.contains("android:id=\"@+id/nav_clip_header\"")
        )
        assertTrue("nav_emoji_header exists inside top_middle_slot",
            layoutXml.contains("android:id=\"@+id/nav_emoji_header\"")
        )
        assertTrue("btn_backspace exists with BackspaceButton",
            layoutXml.contains("android:id=\"@+id/btn_backspace\"")
        )
    }

    @Test
    fun testBottomRowPermanentSymmetry() {
        // Permanent 5 keys: switch(52), emoji(52), space(weight 1), period(52), enter(52)
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_left_action\""))
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_emoji\""))
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_space_bar\""))
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_period\""))
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_enter\""))

        // btn_emoji is permanent emoji icon without back content
        assertTrue(layoutXml.contains("android:id=\"@+id/btn_emoji_icon\""))
        assertTrue(!layoutXml.contains("android:id=\"@+id/btn_emoji_back_content\""))

        // btn_period is permanent period TextView without backspace
        assertTrue(!layoutXml.contains("android:id=\"@+id/btn_period_backspace\""))
    }
}
