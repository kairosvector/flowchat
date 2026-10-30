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
 * Fork change (2026-10-30): last transcription result only.
 * Only the single most recent record is kept, so the keyboard panel
 * can show it after insertion. There is deliberately no history list.
 * Records are stored only in this app's private storage on the device.
 * Nothing is uploaded, no target app name and no API key is recorded.
 * polishedText is filled by the fork2 LLM polish step when polishing
 * succeeds, and stays null when polishing is skipped or fails.
 */

package com.example.whispertoinput

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The single most recent transcription record.
 *
 * @param timestampMillis epoch millis; display with the device's local time.
 * @param rawText the text actually inserted (already post-processed,
 *   e.g. converted to Traditional Chinese), trimmed of the trailing
 *   space / newline added by the space / enter keys.
 * @param polishedText reserved for a future LLM polish step; null for now.
 */
data class TranscriptionRecord(
    val timestampMillis: Long,
    val rawText: String,
    val polishedText: String? = null
)

object TranscriptionHistory {
    private const val FILE_NAME = "transcription_history.json"
    private const val KEY_TIMESTAMP = "timestampMillis"
    private const val KEY_RAW_TEXT = "rawText"
    private const val KEY_POLISHED_TEXT = "polishedText"

    /**
     * Overwrite the stored record with the latest one. Never throws:
     * a storage failure must never break text insertion, so every
     * failure is silently swallowed.
     */
    fun add(context: Context, rawText: String) {
        add(context, rawText, null)
    }

    /** Same as [add], but also records the polished text (fork2). */
    fun add(context: Context, rawText: String, polishedText: String?) {
        try {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return
            val polished = polishedText?.trim()?.ifEmpty { null }
            save(
                context,
                TranscriptionRecord(System.currentTimeMillis(), trimmed, polished)
            )
        } catch (_: Exception) {
            // Intentionally silent: history is best-effort only.
        }
    }

    /** Returns the last record, or null if there is none / it is unreadable. */
    fun loadLast(context: Context): TranscriptionRecord? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val text = obj.optString(KEY_RAW_TEXT, "")
            if (text.isEmpty()) return null
            val polished = if (obj.isNull(KEY_POLISHED_TEXT)) null
                else obj.optString(KEY_POLISHED_TEXT, "").ifEmpty { null }
            TranscriptionRecord(
                timestampMillis = obj.optLong(KEY_TIMESTAMP, 0L),
                rawText = text,
                polishedText = polished
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // Intentionally silent.
        }
    }

    private fun save(context: Context, record: TranscriptionRecord) {
        val obj = JSONObject()
        obj.put(KEY_TIMESTAMP, record.timestampMillis)
        obj.put(KEY_RAW_TEXT, record.rawText)
        if (record.polishedText != null) {
            obj.put(KEY_POLISHED_TEXT, record.polishedText)
        } else {
            obj.put(KEY_POLISHED_TEXT, JSONObject.NULL)
        }
        // Write to a temp file first, then rename, so a crash mid-write
        // cannot leave a half-written file behind.
        val dir = context.filesDir
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(obj.toString())
        val target = File(dir, FILE_NAME)
        if (!tmp.renameTo(target)) {
            target.writeText(obj.toString())
            tmp.delete()
        }
    }
}
