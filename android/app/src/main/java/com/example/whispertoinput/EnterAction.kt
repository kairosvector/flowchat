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

import android.view.inputmethod.EditorInfo

/**
 * What the Enter key does after the result card's text was inserted
 * (SPEC_r8 §9.2): run the field's own action (send, go, search, done, next)
 * when it has one, otherwise type a newline. Pure, so it is unit tested.
 */
object EnterAction {
    /**
     * The `IME_ACTION_*` to perform for [imeOptions], or null when Enter
     * should be sent as a plain key press (newline).
     */
    fun editorAction(imeOptions: Int): Int? {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT -> action
            else -> null
        }
    }
}
