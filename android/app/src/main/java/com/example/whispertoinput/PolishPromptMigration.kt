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

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Fork6: one-time move of the saved polish prompt to the new default.
 *
 * The prompt itself lives in the settings DataStore (LLM_PROMPT); only the
 * "which default version has been dealt with" number lives in
 * SharedPreferences. When that number is behind POLISH_PROMPT_VERSION:
 *  - a saved prompt that is empty, or exactly an earlier default (fork5, or
 *    the round 1 - 3 one), was never customised, so it is replaced with the
 *    new default;
 *  - anything else was edited by the user and is left untouched;
 *  - either way the version is recorded, so this runs only once.
 */
object PolishPromptMigration {
    private const val PREFS_NAME = "whisper_input_prefs"
    private const val KEY_PROMPT_VERSION = "polish_prompt_version"

    /**
     * Records that the saved prompt is up to date (the user saved or reset it
     * on the settings page), so the one-time migration never touches it.
     */
    fun markCurrent(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PROMPT_VERSION, POLISH_PROMPT_VERSION).apply()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Safe to call from any coroutine and any number of times. */
    suspend fun runIfNeeded(context: Context) {
        try {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_PROMPT_VERSION, 0) >= POLISH_PROMPT_VERSION) return

            val saved: String? = appContext.dataStore.data.map { preferences ->
                preferences[LLM_PROMPT]
            }.first()
            // null = nothing saved yet (the settings page will seed the
            // new default itself); blank or fork5 default = never edited.
            if (saved != null && (saved.isBlank() || saved == LEGACY_POLISH_PROMPT_V1 ||
                    saved == LEGACY_POLISH_PROMPT_V2)) {
                appContext.dataStore.edit { settings ->
                    settings[LLM_PROMPT] = DEFAULT_POLISH_PROMPT
                }
            }
            prefs.edit().putInt(KEY_PROMPT_VERSION, POLISH_PROMPT_VERSION).apply()
        } catch (_: Exception) {
            // Best effort: a failure leaves the version unrecorded, so
            // the next start simply tries again.
        }
    }
}
