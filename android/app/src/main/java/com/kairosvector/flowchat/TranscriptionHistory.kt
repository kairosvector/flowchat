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

package com.kairosvector.flowchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One transcription record (the history keeps the newest [HistoryCodec.MAX_RECORDS]).
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

/**
 * Pure read / write of the history file text: no Android needed, so it is
 * unit tested. The file is a JSON array, newest first. The v19 file held a
 * single JSON object; [parse] still reads that.
 */
object HistoryCodec {
    const val MAX_RECORDS = 20
    private const val KEY_TIMESTAMP = "timestampMillis"
    private const val KEY_RAW_TEXT = "rawText"
    private const val KEY_POLISHED_TEXT = "polishedText"

    /** Records in file order (newest first); anything unreadable gives an empty list. */
    fun parse(text: String): List<TranscriptionRecord> {
        return try {
            val trimmed = text.trim()
            val objects = if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                listOf(JSONObject(trimmed))
            }
            objects.mapNotNull { fromJson(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(records: List<TranscriptionRecord>): String {
        val array = JSONArray()
        for (record in records.take(MAX_RECORDS)) {
            val obj = JSONObject()
            obj.put(KEY_TIMESTAMP, record.timestampMillis)
            obj.put(KEY_RAW_TEXT, record.rawText)
            obj.put(KEY_POLISHED_TEXT, record.polishedText ?: JSONObject.NULL)
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * [record] goes to the front. A repeat of the newest record's original
     * text (a re-polish of the same transcript) replaces it instead of
     * adding a second entry. At most [MAX_RECORDS] are kept.
     */
    fun add(existing: List<TranscriptionRecord>, record: TranscriptionRecord): List<TranscriptionRecord> {
        val rest = if (existing.firstOrNull()?.rawText == record.rawText) existing.drop(1) else existing
        return (listOf(record) + rest).take(MAX_RECORDS)
    }

    private fun fromJson(obj: JSONObject): TranscriptionRecord? {
        val text = obj.optString(KEY_RAW_TEXT, "")
        if (text.isEmpty()) return null
        val polished = if (obj.isNull(KEY_POLISHED_TEXT)) null
            else obj.optString(KEY_POLISHED_TEXT, "").ifEmpty { null }
        return TranscriptionRecord(
            timestampMillis = obj.optLong(KEY_TIMESTAMP, 0L),
            rawText = text,
            polishedText = polished
        )
    }
}

object TranscriptionHistory {
    private const val FILE_NAME = "transcription_history.json"

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
            val record = TranscriptionRecord(System.currentTimeMillis(), trimmed, polished)
            save(context, HistoryCodec.add(loadAll(context), record))
        } catch (_: Exception) {
            // Intentionally silent: history is best-effort only.
        }
    }

    /** Returns the last record, or null if there is none / it is unreadable. */
    fun loadLast(context: Context): TranscriptionRecord? = loadAll(context).firstOrNull()

    /** All records, newest first (at most [HistoryCodec.MAX_RECORDS]); empty when unreadable. */
    fun loadAll(context: Context): List<TranscriptionRecord> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) emptyList() else HistoryCodec.parse(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Removes the record at [index] (0 = newest). Never throws. */
    fun delete(context: Context, index: Int) {
        try {
            val all = loadAll(context)
            if (index !in all.indices) return
            save(context, all.filterIndexed { i, _ -> i != index })
        } catch (_: Exception) {
            // Best-effort only.
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

    private fun save(context: Context, records: List<TranscriptionRecord>) {
        val json = HistoryCodec.encode(records)
        // Write to a temp file first, then rename, so a crash mid-write
        // cannot leave a half-written file behind.
        val dir = context.filesDir
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json)
        val target = File(dir, FILE_NAME)
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            tmp.delete()
        }
    }
}
