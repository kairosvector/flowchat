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

package com.example.whispertoinput.keyboard

enum class StatusType {
    ERROR,
    RECORDING,
    PROCESSING,
    TRANSIENT,
    DEFAULT
}

data class ResolvedStatus(
    val type: StatusType,
    val text: String,
    val isError: Boolean = false,
    val isRecording: Boolean = false
)

/**
 * Pure resolver for keyboard status text priority:
 * 1. Error (red, 4s or until cleared)
 * 2. Recording timer (red dot + timer)
 * 3. Processing (transcription / polishing / staged raw)
 * 4. Transient prompt (language, polish on/off, copied, undone, etc.)
 * 5. Default (empty in Idle, "點「插入」放入文字" in Result)
 */
class StatusResolver {
    var errorText: String? = null
        private set
    var errorExpiresAt: Long = 0L
        private set

    var transientText: String? = null
        private set
    var transientExpiresAt: Long = 0L
        private set

    fun setError(text: String, durationMs: Long = 4000L, now: Long = System.currentTimeMillis()) {
        errorText = text
        errorExpiresAt = now + durationMs
    }

    fun setTransient(text: String, durationMs: Long = 2000L, now: Long = System.currentTimeMillis()) {
        transientText = text
        transientExpiresAt = now + durationMs
    }

    fun clearError() {
        errorText = null
        errorExpiresAt = 0L
    }

    fun clearTransient() {
        transientText = null
        transientExpiresAt = 0L
    }

    fun clearAll() {
        clearError()
        clearTransient()
    }

    fun resolve(
        isRecording: Boolean,
        recordingTimerText: String,
        isProcessing: Boolean,
        processingText: String,
        defaultText: String,
        now: Long = System.currentTimeMillis()
    ): ResolvedStatus {
        // 1. Error
        val err = errorText
        if (err != null) {
            if (now < errorExpiresAt) {
                return ResolvedStatus(StatusType.ERROR, err, isError = true)
            } else {
                errorText = null
                errorExpiresAt = 0L
            }
        }

        // 2. Recording timer
        if (isRecording) {
            return ResolvedStatus(StatusType.RECORDING, recordingTimerText, isRecording = true)
        }

        // 3. Processing
        if (isProcessing) {
            return ResolvedStatus(StatusType.PROCESSING, processingText)
        }

        // 4. Transient prompt
        val tr = transientText
        if (tr != null) {
            if (now < transientExpiresAt) {
                return ResolvedStatus(StatusType.TRANSIENT, tr)
            } else {
                transientText = null
                transientExpiresAt = 0L
            }
        }

        // 5. Default
        return ResolvedStatus(StatusType.DEFAULT, defaultText)
    }
}
