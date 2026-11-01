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

/**
 * Fork6: the language code the keyboard's language key cycles through and
 * the transcriber sends. Both go through [normalize], so a value the API
 * would reject (e.g. "multi", "zh-TW" typed by hand) is never sent as is.
 */
object LanguageSettings {
    const val AUTO = "auto"
    const val ZH = "zh"
    const val EN = "en"

    private const val PREFS_NAME = "whisper_input_prefs"
    private const val KEY_CUSTOM_LANGUAGE = "custom_language_code"

    private val CODE_REGEX = Regex("^[a-z]{2,3}$")

    /**
     * Returns an ISO-639 style code (2-3 lowercase letters) or [AUTO].
     * A regional tag such as "zh-TW" / "zh_TW" is cut to its first part
     * ("zh"), because the transcription API takes the bare language code.
     * "multi" (the NVIDIA NIM default), blanks and anything else that is
     * not 2-3 letters means [AUTO].
     */
    fun normalize(raw: String?): String {
        val head = raw.orEmpty().trim().lowercase().split('-', '_').first()
        return if (head != "multi" && CODE_REGEX.matches(head)) head else AUTO
    }

    fun isBuiltIn(code: String): Boolean = code == AUTO || code == ZH || code == EN

    /** The custom code (e.g. "ja") remembered for the language key's cycle. */
    fun loadCustom(context: Context): String? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_LANGUAGE, null)
            ?.let { normalize(it) }
            ?.takeIf { !isBuiltIn(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Keeps the remembered custom code in step with the Language Code
     * setting: a valid non-built-in code is remembered, while auto / zh / en
     * (or something invalid) clears it so it leaves the key's cycle.
     * Called when the settings page applies, and by the keyboard when it
     * reads a custom code it did not know yet.
     */
    fun syncCustomFromSetting(context: Context, rawSetting: String?) {
        try {
            val code = normalize(rawSetting)
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            if (isBuiltIn(code)) editor.remove(KEY_CUSTOM_LANGUAGE)
            else editor.putString(KEY_CUSTOM_LANGUAGE, code)
            editor.apply()
        } catch (_: Exception) {
            // Best effort: without it the cycle is just auto / zh / en.
        }
    }
}
