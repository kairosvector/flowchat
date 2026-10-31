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

import com.example.whispertoinput.keyboard.ResolvedStatus
import com.example.whispertoinput.keyboard.StatusResolver
import com.example.whispertoinput.keyboard.StatusType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatusResolverTest {

    private lateinit var resolver: StatusResolver

    @Before
    fun setUp() {
        resolver = StatusResolver()
    }

    @Test
    fun defaultState_returnsDefaultText() {
        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "● 聆聽中 0:00",
            isProcessing = false,
            processingText = "轉寫中…",
            defaultText = "點「插入」放入文字",
            now = 1000L
        )

        assertEquals(StatusType.DEFAULT, result.type)
        assertEquals("點「插入」放入文字", result.text)
        assertFalse(result.isError)
        assertFalse(result.isRecording)
    }

    @Test
    fun transient_overridesDefault() {
        resolver.setTransient("AI 潤飾已開啟", durationMs = 1500L, now = 1000L)

        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "● 聆聽中 0:00",
            isProcessing = false,
            processingText = "轉寫中…",
            defaultText = "",
            now = 1500L
        )

        assertEquals(StatusType.TRANSIENT, result.type)
        assertEquals("AI 潤飾已開啟", result.text)
        assertFalse(result.isError)
        assertFalse(result.isRecording)
    }

    @Test
    fun transient_expiresAfterDuration() {
        resolver.setTransient("AI 潤飾已開啟", durationMs = 1500L, now = 1000L)

        // At 2501ms, 1500ms has elapsed from 1000ms
        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "● 聆聽中 0:00",
            isProcessing = false,
            processingText = "轉寫中…",
            defaultText = "default",
            now = 2501L
        )

        assertEquals(StatusType.DEFAULT, result.type)
        assertEquals("default", result.text)
    }

    @Test
    fun processing_overridesTransient() {
        resolver.setTransient("語言：中文", durationMs = 2000L, now = 1000L)

        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "● 聆聽中 0:00",
            isProcessing = true,
            processingText = "轉寫中…",
            defaultText = "",
            now = 1200L
        )

        assertEquals(StatusType.PROCESSING, result.type)
        assertEquals("轉寫中…", result.text)
    }

    @Test
    fun recording_overridesProcessingAndTransient() {
        resolver.setTransient("已複製", durationMs = 2000L, now = 1000L)

        val result = resolver.resolve(
            isRecording = true,
            recordingTimerText = "● 聆聽中 0:03",
            isProcessing = true,
            processingText = "轉寫中…",
            defaultText = "",
            now = 1200L
        )

        assertEquals(StatusType.RECORDING, result.type)
        assertEquals("● 聆聽中 0:03", result.text)
        assertTrue(result.isRecording)
        assertFalse(result.isError)
    }

    @Test
    fun error_hasHighestPriority() {
        resolver.setError("轉寫失敗：網路異常", durationMs = 4000L, now = 1000L)
        resolver.setTransient("已複製", durationMs = 2000L, now = 1000L)

        val result = resolver.resolve(
            isRecording = true,
            recordingTimerText = "● 聆聽中 0:03",
            isProcessing = true,
            processingText = "轉寫中…",
            defaultText = "",
            now = 1500L
        )

        assertEquals(StatusType.ERROR, result.type)
        assertEquals("轉寫失敗：網路異常", result.text)
        assertTrue(result.isError)
        assertFalse(result.isRecording)
    }

    @Test
    fun error_expiresAfter4Seconds() {
        resolver.setError("轉寫失敗", durationMs = 4000L, now = 1000L)

        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "",
            isProcessing = false,
            processingText = "",
            defaultText = "idle",
            now = 5001L
        )

        assertEquals(StatusType.DEFAULT, result.type)
        assertEquals("idle", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun clearError_immediatelyRestoresNextPriority() {
        resolver.setError("轉寫失敗", durationMs = 4000L, now = 1000L)
        resolver.clearError()

        val result = resolver.resolve(
            isRecording = false,
            recordingTimerText = "",
            isProcessing = false,
            processingText = "",
            defaultText = "ready",
            now = 1500L
        )

        assertEquals(StatusType.DEFAULT, result.type)
        assertEquals("ready", result.text)
    }
}
