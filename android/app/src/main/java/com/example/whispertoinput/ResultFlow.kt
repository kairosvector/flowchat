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

package com.example.whispertoinput

/** What the keyboard does with a finished transcription. */
enum class ResultMode {
    /** Polished: the result card waits for "insert" (SPEC_r8 RESULT_PENDING). */
    PENDING_CARD,

    /**
     * Polishing was tried and failed (round 12, Kevy 2026-10-31): the original
     * goes into the editor right away, as in v19; the card is shown as already
     * inserted, with the "AI 潤飾失敗 · 重試" notice that can replace the text.
     */
    INSERTED_WITH_RETRY,

    /** AI off, or a sentence too short to polish: the text goes in, no card. */
    DIRECT_INSERT
}

object ResultFlow {
    fun forNote(note: PolishNote): ResultMode = when (note) {
        PolishNote.OK -> ResultMode.PENDING_CARD
        PolishNote.FAILED -> ResultMode.INSERTED_WITH_RETRY
        PolishNote.OFF, PolishNote.TOO_SHORT -> ResultMode.DIRECT_INSERT
    }
}
