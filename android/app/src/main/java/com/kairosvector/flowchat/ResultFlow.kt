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

/** What the keyboard does with a finished transcription (round 13, SPEC_r8 §21). */
enum class ResultMode {
    /** Polished: text is inserted directly, card is shown with tone and actions. */
    INSERTED_POLISHED,

    /**
     * Polishing failed or timed out: the original was inserted directly,
     * and the card offers the retry that swaps it.
     */
    INSERTED_FAILED_RETRY,

    /** AI off, or a sentence too short to polish: the text goes in, no card. */
    DIRECT_INSERT
}

object ResultFlow {
    fun forNote(note: PolishNote): ResultMode = when (note) {
        PolishNote.OK -> ResultMode.INSERTED_POLISHED
        PolishNote.FAILED -> ResultMode.INSERTED_FAILED_RETRY
        PolishNote.OFF, PolishNote.TOO_SHORT -> ResultMode.DIRECT_INSERT
    }
}

object InsertionChecker {
    /**
     * Pure matcher for undo / replace: returns true only if [inserted] is non-empty,
     * no text is selected, and [textBeforeCursor] ends with [inserted].
     */
    fun canReplaceOrUndo(
        textBeforeCursor: CharSequence?,
        selectedText: CharSequence?,
        inserted: String
    ): Boolean {
        if (inserted.isEmpty()) return false
        if (!selectedText.isNullOrEmpty()) return false
        if (textBeforeCursor == null) return false
        return textBeforeCursor.endsWith(inserted)
    }
}
