/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ROUND 13 auto-insert behavior (SPEC_r8 §21).
 */
class AutoInsertTest {

    @Test
    fun polishNoteRoutesToExpectedResultMode() {
        // AI OK -> insert polished text with card
        assertEquals(ResultMode.INSERTED_POLISHED, ResultFlow.forNote(PolishNote.OK))
        // AI failed / timeout -> insert raw text with retry card
        assertEquals(ResultMode.INSERTED_FAILED_RETRY, ResultFlow.forNote(PolishNote.FAILED))
        // AI OFF or sentence too short -> direct insert, no card
        assertEquals(ResultMode.DIRECT_INSERT, ResultFlow.forNote(PolishNote.OFF))
        assertEquals(ResultMode.DIRECT_INSERT, ResultFlow.forNote(PolishNote.TOO_SHORT))
    }

    @Test
    fun polishTimeoutConstantIsEightSeconds() {
        assertEquals(8000L, POLISH_TIMEOUT_MS)
    }

    @Test
    fun insertionCheckerAllowsUndoWhenCursorImmediatelyAfterInserted() {
        // Exact match
        assertTrue(InsertionChecker.canReplaceOrUndo("今天天氣真好", null, "今天天氣真好"))
        // Prefix in text box before inserted text
        assertTrue(InsertionChecker.canReplaceOrUndo("你好，今天天氣真好", null, "今天天氣真好"))
        // Empty selected text
        assertTrue(InsertionChecker.canReplaceOrUndo("今天天氣真好", "", "今天天氣真好"))
    }

    @Test
    fun insertionCheckerRejectsWhenTextHasChangedOrCursorMoved() {
        val inserted = "今天天氣真好"

        // Cursor moved to the middle of the inserted text
        assertFalse(InsertionChecker.canReplaceOrUndo("今天天氣", null, inserted))
        // User typed an extra character after the inserted text
        assertFalse(InsertionChecker.canReplaceOrUndo("今天天氣真好！", null, inserted))
        // User has an active selection
        assertFalse(InsertionChecker.canReplaceOrUndo("今天天氣真好", "好", inserted))
        // Text before cursor is null
        assertFalse(InsertionChecker.canReplaceOrUndo(null, null, inserted))
        // Text before cursor is completely different
        assertFalse(InsertionChecker.canReplaceOrUndo("完全不同的文字", null, inserted))
        // Inserted string is empty
        assertFalse(InsertionChecker.canReplaceOrUndo("今天天氣真好", null, ""))
    }

    @Test
    fun insertionCheckerHandlesWhitespaceAndNewlineSuffixes() {
        val insertedWithSpace = "Hello world "
        assertTrue(InsertionChecker.canReplaceOrUndo("Prefix Hello world ", null, insertedWithSpace))
        assertFalse(InsertionChecker.canReplaceOrUndo("Prefix Hello world", null, insertedWithSpace))

        val insertedWithNewline = "Line one\n"
        assertTrue(InsertionChecker.canReplaceOrUndo("Line one\n", null, insertedWithNewline))
        assertFalse(InsertionChecker.canReplaceOrUndo("Line one", null, insertedWithNewline))
    }
}
