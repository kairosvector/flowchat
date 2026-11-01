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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/** The tones in order, and which one is current ([TONE_OFF] = polishing off). */
data class ToneState(val tones: List<Tone>, val currentId: String) {
    val polishOn: Boolean get() = currentId != TONE_OFF
    val current: Tone? get() = tones.firstOrNull { it.id == currentId }
    fun byId(id: String?): Tone? = tones.firstOrNull { it.id == id }
}

/** Reads and writes the tone settings; the keyboard and the settings page share it. */
object ToneStore {
    /**
     * What the settings say right now. Before the migration has run (or when
     * `tones-json` is unreadable) the answer is what the migration would
     * produce, so nothing ever sees an empty list.
     */
    fun resolve(prefs: Preferences): ToneState {
        val saved = ToneCodec.parse(prefs[TONES_JSON])
        val migrated = if (saved == null) ToneMigration.migrate(prefs[LLM_PROMPT], prefs[POLISH_ENABLED]) else null
        val tones = saved ?: migrated!!.tones
        val stored = prefs[POLISH_TONE]
        val current = when {
            stored == TONE_OFF -> TONE_OFF
            stored != null && tones.any { it.id == stored } -> stored
            stored == null && migrated != null -> migrated.polishTone
            stored == null && prefs[POLISH_ENABLED] == false -> TONE_OFF
            else -> tones.first().id
        }
        return ToneState(tones, current)
    }

    suspend fun load(context: Context): ToneState = try {
        resolve(context.applicationContext.dataStore.data.first())
    } catch (_: Exception) {
        ToneState(TonePrompts.defaultTones(), TONE_PRO)
    }

    /** Makes [id] ("off" or a tone id) the current tone and keeps `polish-enabled` equal to it. */
    suspend fun select(context: Context, id: String) {
        context.applicationContext.dataStore.edit { settings ->
            settings[POLISH_TONE] = id
            settings[POLISH_ENABLED] = id != TONE_OFF
        }
    }

    /**
     * Saves the ordered list. If the current tone is no longer in it, the
     * first tone becomes current (an "off" choice stays "off").
     */
    suspend fun saveTones(context: Context, tones: List<Tone>) {
        context.applicationContext.dataStore.edit { settings ->
            settings[TONES_JSON] = ToneCodec.encode(tones)
            val current = settings[POLISH_TONE]
            if (current != TONE_OFF && tones.none { it.id == current }) {
                settings[POLISH_TONE] = tones.first().id
            }
        }
    }
}
