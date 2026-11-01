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
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Round 11b: the one-time move from the single `llm-prompt` / `polish-enabled`
 * settings to the tone list (SPEC_r8 §15, prompt version 3 -> 4).
 *
 * The rules themselves are [ToneMigration] (pure, unit tested); this only
 * reads the settings, writes `tones-json` (and `polish-tone` when it is not
 * there yet) and records that it ran. It runs only while `tones-json` is
 * missing, so a second call, or a call after the user edited the tones,
 * changes nothing. `llm-prompt` is only read, never written or deleted.
 */
object PolishPromptMigration {
    private const val PREFS_NAME = "whisper_input_prefs"
    private const val KEY_PROMPT_VERSION = "polish_prompt_version"

    /** Safe to call from any coroutine and any number of times. */
    suspend fun runIfNeeded(context: Context) {
        try {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = appContext.dataStore.data.first()
            if (ToneCodec.parse(current[TONES_JSON]) == null) {
                val outcome = ToneMigration.migrate(current[LLM_PROMPT], current[POLISH_ENABLED])
                appContext.dataStore.edit { settings ->
                    settings[TONES_JSON] = ToneCodec.encode(outcome.tones)
                    if (settings[POLISH_TONE] == null) {
                        settings[POLISH_TONE] = outcome.polishTone
                    }
                    settings[POLISH_ENABLED] = settings[POLISH_TONE] != TONE_OFF
                }
            }
            prefs.edit().putInt(KEY_PROMPT_VERSION, POLISH_PROMPT_VERSION).apply()
        } catch (_: Exception) {
            // Best effort: ToneStore.resolve gives the same answer in memory
            // until the next start tries again.
        }
    }
}
