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

import android.content.Context
import androidx.annotation.StringRes

/**
 * What the keyboard shows as the description of a tone. The two built-in
 * tones always use the string resources (so a wording change reaches users
 * whose `tones-json` already holds the old text); custom tones use their own.
 */
object ToneDisplay {
    @StringRes
    fun descRes(tone: Tone): Int? {
        if (!tone.builtin) return null
        return when (tone.id) {
            TONE_PRO -> R.string.kb_tone_pro_desc
            TONE_CHAT -> R.string.kb_tone_chat_desc
            else -> null
        }
    }

    fun desc(context: Context, tone: Tone): String =
        descRes(tone)?.let { context.getString(it) }
            ?: tone.desc.ifBlank { context.getString(R.string.set_tone_new_desc) }
}
