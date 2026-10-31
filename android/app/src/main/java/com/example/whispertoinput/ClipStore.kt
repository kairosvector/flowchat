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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

val CLIP_HISTORY_ENABLED = booleanPreferencesKey("clip-history-enabled")
val CLIP_HISTORY_JSON = stringPreferencesKey("clip-history-json")
val CLIP_DISMISSED = stringPreferencesKey("clip-dismissed")

/**
 * Where the keyboard keeps clipboard history (SPEC_r8 §13.4, §15). Ordinary text goes to
 * `clip-history-json`; masked clips and verification codes only live in [memory], which is
 * lost when the process ends. Clipboard text is never written to any log.
 */
object ClipStore {
    /** Process-wide, never persisted (masked clips and verification codes). */
    @Volatile
    var memory: List<MemoryClip> = emptyList()
        private set

    suspend fun enabled(context: Context): Boolean = try {
        context.applicationContext.dataStore.data.first()[CLIP_HISTORY_ENABLED] ?: true
    } catch (_: Exception) {
        true
    }

    /** The history without what is older than a day (the file is cleaned up when it changed). */
    suspend fun history(context: Context, now: Long = System.currentTimeMillis()): List<ClipRecord> = try {
        val appContext = context.applicationContext
        val json = appContext.dataStore.data.first()[CLIP_HISTORY_JSON]
        val records = ClipHistory.parse(json, now)
        if (json != null && ClipHistory.encode(records) != json) {
            appContext.dataStore.edit { it[CLIP_HISTORY_JSON] = ClipHistory.encode(records) }
        }
        records
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Keeps a clip the keyboard has seen: ordinary text in the history file, everything else
     * in memory only. Nothing is kept while the "記錄剪貼簿" switch is off.
     */
    suspend fun record(context: Context, text: String, timestampMillis: Long, kind: ClipKind) {
        if (text.isEmpty() || !enabled(context)) return
        val now = System.currentTimeMillis()
        if (kind.persistable) {
            try {
                context.applicationContext.dataStore.edit { settings ->
                    val current = ClipHistory.parse(settings[CLIP_HISTORY_JSON], now)
                    settings[CLIP_HISTORY_JSON] =
                        ClipHistory.encode(ClipHistory.add(current, text, timestampMillis, now))
                }
            } catch (_: Exception) {
                // Best effort: the clip just is not kept.
            }
        } else {
            memory = ClipMemory.add(memory, text, timestampMillis, kind, now)
        }
    }

    /** The keyboard closed: masked clips go, verification codes stay until they expire. */
    fun keyboardClosed() {
        memory = ClipMemory.purge(memory, System.currentTimeMillis(), keyboardClosed = true)
    }

    fun expire() {
        memory = ClipMemory.purge(memory, System.currentTimeMillis(), keyboardClosed = false)
    }

    /** Empties the history file and the in-memory clips (not the system clipboard, not the transcription history). */
    suspend fun clearAll(context: Context) {
        memory = emptyList()
        try {
            context.applicationContext.dataStore.edit {
                it.remove(CLIP_HISTORY_JSON)
                it.remove(CLIP_DISMISSED)
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** How many clips are kept (history file plus memory), for the settings page. */
    suspend fun count(context: Context): Int =
        history(context).size + ClipMemory.purge(memory, System.currentTimeMillis(), false).size

    suspend fun dismissedKey(context: Context): String? = try {
        context.applicationContext.dataStore.data.first()[CLIP_DISMISSED]
    } catch (_: Exception) {
        null
    }

    suspend fun setDismissed(context: Context, key: String) {
        try {
            context.applicationContext.dataStore.edit { it[CLIP_DISMISSED] = key }
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
