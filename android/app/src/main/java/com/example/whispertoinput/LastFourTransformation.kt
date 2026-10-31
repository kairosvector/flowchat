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

import android.text.method.PasswordTransformationMethod
import android.view.View

/** How an API key is shown while it is "hidden": only the last 4 characters are visible. */
object ApiKeyMask {
    const val BULLET = '•'
    const val VISIBLE_TAIL = 4

    /**
     * Number of trailing characters left visible for a key of [length]
     * characters. A key of 4 characters or fewer is hidden completely (showing
     * the tail of such a key would show all of it).
     */
    fun visibleCount(length: Int): Int = if (length > VISIBLE_TAIL) VISIBLE_TAIL else 0

    fun mask(text: CharSequence): String {
        val visible = visibleCount(text.length)
        val hidden = text.length - visible
        return buildString(text.length) {
            repeat(hidden) { append(BULLET) }
            append(text, hidden, text.length)
        }
    }
}

/**
 * Shows "••••••••••••3f9a": every character but the last 4 as a bullet. The
 * built-in password toggle can only hide everything, so the settings page
 * switches between this and `null` itself.
 */
class LastFourTransformation : PasswordTransformationMethod() {
    override fun getTransformation(source: CharSequence, view: View): CharSequence =
        MaskedChars(source)

    private class MaskedChars(private val source: CharSequence) : CharSequence {
        override val length: Int get() = source.length

        override fun get(index: Int): Char =
            if (index < source.length - ApiKeyMask.visibleCount(source.length)) ApiKeyMask.BULLET
            else source[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            toString().subSequence(startIndex, endIndex)

        override fun toString(): String = ApiKeyMask.mask(source)
    }
}
