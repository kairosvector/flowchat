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

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * What a piece of clipboard text is (SPEC_r8 §13.3). Pure, so every rule is a unit test.
 * [masked] kinds are shown as dots and never written to disk; [OTP] is shown as it is
 * but only kept in memory; [TEXT] and [URL] are ordinary and may be kept in the history.
 */
enum class ClipKind(val masked: Boolean, val persistable: Boolean) {
    TEXT(masked = false, persistable = true),
    URL(masked = false, persistable = true),
    OTP(masked = false, persistable = false),
    SENSITIVE(masked = true, persistable = false),
    CARD(masked = true, persistable = false),
    PASSWORD(masked = true, persistable = false),
    ID_NUMBER(masked = true, persistable = false)
}

object ClipClassifier {
    /** The key some apps (and Android 13+) put on a clip that holds something secret. */
    const val IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

    private val keyPrefixes = listOf("sk-", "gsk_", "ghp_", "AKIA", "xox")
    private val otpWords = Regex("驗證碼|認證碼|OTP|code", RegexOption.IGNORE_CASE)
    private val fourToEightDigits = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val url = Regex("^(https?|ftp)://\\S+$", RegexOption.IGNORE_CASE)
    private val idNumber = Regex("^[A-Z][12]\\d{8}$")

    /**
     * Judged in the order of SPEC_r8 §13.3: the sensitive flag, a verification code,
     * a card number, a web address, something that looks like a password or key, an ID number.
     * Note: [url] MUST be checked before [looksLikePassword]. A standard URL typically contains
     * uppercase, lowercase, digits, and punctuation/symbols without whitespace, which would
     * otherwise satisfy [looksLikePassword] and be wrongly classified as a password/secret.
     * An address is ordinary/persistable and only masked when the system flag says so.
     */
    fun classify(text: String, sensitiveFlag: Boolean): ClipKind {
        if (sensitiveFlag) return ClipKind.SENSITIVE
        if (isOtp(text)) return ClipKind.OTP
        if (isCardNumber(text)) return ClipKind.CARD
        if (url.matches(text.trim())) return ClipKind.URL
        if (looksLikePassword(text)) return ClipKind.PASSWORD
        if (idNumber.matches(text.trim())) return ClipKind.ID_NUMBER
        return ClipKind.TEXT
    }

    /** 4–8 digits (spaces ignored), or a text with 驗證碼 / 認證碼 / OTP / code and a 4–8 digit number. */
    fun isOtp(text: String): Boolean {
        val compact = text.filter { !it.isWhitespace() }
        if (compact.length in 4..8 && compact.all { it in '0'..'9' }) return true
        return otpWords.containsMatchIn(text) && fourToEightDigits.containsMatchIn(text)
    }

    /** 13–19 digits (spaces and dashes ignored) that pass the Luhn check. */
    fun isCardNumber(text: String): Boolean {
        val digits = text.filter { !it.isWhitespace() && it != '-' }
        if (digits.length !in 13..19 || !digits.all { it in '0'..'9' }) return false
        return luhn(digits)
    }

    fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    /** No spaces, 8–64 characters, and three of upper / lower / digit / symbol, or a known key prefix. */
    fun looksLikePassword(text: String): Boolean {
        if (text.length !in 8..64 || text.any { it.isWhitespace() }) return false
        if (keyPrefixes.any { text.startsWith(it) }) return true
        var classes = 0
        if (text.any { it.isUpperCase() }) classes++
        if (text.any { it.isLowerCase() }) classes++
        if (text.any { it.isDigit() }) classes++
        if (text.any { !it.isLetterOrDigit() }) classes++
        return classes >= 3
    }
}

/** The one-line preview of a clip on the paste chip (SPEC_r8 §13.1). */
object ClipPreview {
    const val MAX_CHARS = 40

    /** Runs of spaces / newlines become one space; at most [MAX_CHARS] characters (the view cuts the rest by width). */
    fun of(text: String, maxChars: Int = MAX_CHARS): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        val count = oneLine.codePointCount(0, oneLine.length)
        if (count <= maxChars) return oneLine
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, maxChars))
    }
}

/** One kept clip. */
data class ClipRecord(val text: String, val timestampMillis: Long)

/**
 * The ordinary clipboard history (SPEC_r8 §13.4, ROUND 15 MVP): newest first, at most 5 entries,
 * none older than 24 hours, the same text only once. Only [ClipKind.persistable] text may be
 * given to [add]; this is the file format of `clip-history-json`.
 */
object ClipHistory {
    const val MAX_RECORDS = 5
    const val TTL_MILLIS = 24L * 60 * 60 * 1000

    fun parse(json: String?, now: Long): List<ClipRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val records = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "")
                if (text.isEmpty()) null else ClipRecord(text, o.optLong("ts", 0L))
            }
            purge(records, now)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(records: List<ClipRecord>): String {
        val array = JSONArray()
        for (r in records.take(MAX_RECORDS)) {
            array.put(JSONObject().put("text", r.text).put("ts", r.timestampMillis))
        }
        return array.toString()
    }

    /** Drops what is older than 24 hours or beyond the 20th entry. */
    fun purge(records: List<ClipRecord>, now: Long): List<ClipRecord> =
        records.filter { now - it.timestampMillis <= TTL_MILLIS }.take(MAX_RECORDS)

    /** [text] goes to the front (an equal older entry is removed first). */
    fun add(records: List<ClipRecord>, text: String, timestampMillis: Long, now: Long): List<ClipRecord> {
        if (text.isEmpty()) return purge(records, now)
        val rest = records.filter { it.text != text }
        return purge(listOf(ClipRecord(text, timestampMillis)) + rest, now)
    }
}

/**
 * Clips that must never reach the disk: masked ones and verification codes. They live in
 * memory for two minutes; the masked ones are also dropped when the keyboard closes.
 */
data class MemoryClip(val text: String, val timestampMillis: Long, val kind: ClipKind)

object ClipMemory {
    const val TTL_MILLIS = 2L * 60 * 1000

    fun add(list: List<MemoryClip>, text: String, timestampMillis: Long, kind: ClipKind, now: Long): List<MemoryClip> {
        require(!kind.persistable) { "ordinary text belongs in ClipHistory" }
        return purge(listOf(MemoryClip(text, timestampMillis, kind)) + list.filter { it.text != text }, now, false)
    }

    /** Drops what is two minutes old; with [keyboardClosed] also every masked clip. */
    fun purge(list: List<MemoryClip>, now: Long, keyboardClosed: Boolean): List<MemoryClip> =
        list.filter { now - it.timestampMillis <= TTL_MILLIS && !(keyboardClosed && it.kind.masked) }
}

/** One row of the clipboard list. */
data class ClipItem(val text: String, val timestampMillis: Long, val kind: ClipKind, val isCurrent: Boolean)

object ClipList {
    /**
     * The current system clipboard first (when it is text), then the rest by time: the
     * ordinary history and the in-memory clips, without a second row for the same text.
     * When [current] is non-persistable (a verification code or sensitive/masked clip),
     * it is omitted once its age exceeds [ClipMemory.TTL_MILLIS] (2 minutes).
     */
    fun build(
        current: ClipItem?,
        history: List<ClipRecord>,
        memory: List<MemoryClip>,
        now: Long
    ): List<ClipItem> {
        val validCurrent = if (current != null && !current.kind.persistable && (now - current.timestampMillis > ClipMemory.TTL_MILLIS)) {
            null
        } else {
            current
        }
        val rest = (
            history.map { ClipItem(it.text, it.timestampMillis, ClipClassifier.classify(it.text, false), false) } +
                ClipMemory.purge(memory, now, false).map { ClipItem(it.text, it.timestampMillis, it.kind, false) }
            ).sortedByDescending { it.timestampMillis }
        val all = listOfNotNull(validCurrent) + rest
        return all.distinctBy { it.text }
    }
}

/** When the paste chip is shown (SPEC_r8 §13.1, IA_r8 §3). */
object PasteChip {
    const val WINDOW_MILLIS = 2L * 60 * 1000

    /** Identifies one clip: its text and when it was copied. The text itself is never stored. */
    fun key(text: String, timestampMillis: Long): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) } + ":" + timestampMillis
    }

    /** Copied at most two minutes ago, not pasted yet, not dismissed. */
    fun visible(item: ClipItem?, now: Long, pastedKeys: Set<String>, dismissedKey: String?): Boolean {
        item ?: return false
        if (now - item.timestampMillis > WINDOW_MILLIS) return false
        val key = key(item.text, item.timestampMillis)
        return key !in pastedKeys && key != dismissedKey
    }
}
