package com.example.whispertoinput

import com.example.whispertoinput.keyboard.ClipReadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardDiagnosisTest {

    @Test
    fun testClipReadStatusEnumValues() {
        val values = ClipReadStatus.values()
        assertEquals(4, values.size)
        assertEquals(ClipReadStatus.NONE, ClipReadStatus.valueOf("NONE"))
        assertEquals(ClipReadStatus.SUCCESS, ClipReadStatus.valueOf("SUCCESS"))
        assertEquals(ClipReadStatus.EMPTY, ClipReadStatus.valueOf("EMPTY"))
        assertEquals(ClipReadStatus.DENIED, ClipReadStatus.valueOf("DENIED"))
    }

    @Test
    fun testDiagnosticStatusMapping() {
        // Status mapping to Chinese UI strings
        val map = mapOf(
            ClipReadStatus.SUCCESS to "有讀到",
            ClipReadStatus.EMPTY to "讀到空的",
            ClipReadStatus.DENIED to "被系統拒絕"
        )
        assertEquals("有讀到", map[ClipReadStatus.SUCCESS])
        assertEquals("讀到空的", map[ClipReadStatus.EMPTY])
        assertEquals("被系統拒絕", map[ClipReadStatus.DENIED])
    }
}
