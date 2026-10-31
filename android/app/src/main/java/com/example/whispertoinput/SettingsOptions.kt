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

import androidx.annotation.StringRes

/**
 * Fork6 round 6: the settings page shows translated labels but must keep
 * storing the original English strings (WhisperTranscriber and
 * WhisperInputService compare the stored value with them). Every option is a
 * pair of the stored value and the label to show.
 *
 * The stored strings below mirror values/donottranslate.xml; a unit test
 * (SettingsOptionsTest) checks that the two stay identical.
 */
data class StoredOption(
    val stored: String,
    @StringRes val label: Int,
    @StringRes val sub: Int? = null
)

object SettingsOptions {
    const val BACKEND_OPENAI = "OpenAI API"
    const val BACKEND_ASR = "Whisper ASR Webservice"
    const val BACKEND_NIM = "NVIDIA NIM"

    const val POSTPROCESS_TRADITIONAL = "Convert to Traditional Chinese"
    const val POSTPROCESS_SIMPLIFIED = "Convert to Simplified Chinese"
    const val POSTPROCESS_NONE = "No Conversion"

    const val DEFAULT_ENDPOINT_OPENAI = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val DEFAULT_ENDPOINT_ASR = "http://localhost:9000/asr"
    const val DEFAULT_ENDPOINT_NIM = "http://localhost:9000/v1/audio/transcriptions"
    const val DEFAULT_MODEL_OPENAI = "whisper-large-v3-turbo"
    const val LANGUAGE_MULTI = "multi"

    val backends: List<StoredOption> = listOf(
        StoredOption(BACKEND_OPENAI, R.string.st_backend_openai, R.string.st_backend_openai_sub),
        StoredOption(BACKEND_ASR, R.string.st_backend_asr, R.string.st_backend_asr_sub),
        StoredOption(BACKEND_NIM, R.string.st_backend_nim, R.string.st_backend_nim_sub),
    )

    val postprocessing: List<StoredOption> = listOf(
        StoredOption(POSTPROCESS_TRADITIONAL, R.string.st_pp_traditional),
        StoredOption(POSTPROCESS_SIMPLIFIED, R.string.st_pp_simplified),
        StoredOption(POSTPROCESS_NONE, R.string.st_pp_none),
    )

    /** Index of the option whose stored value is [stored], or -1 for a value we do not know. */
    fun indexOfStored(options: List<StoredOption>, stored: String?): Int =
        options.indexOfFirst { it.stored == stored }

    /** The value to store for the option at [index]. */
    fun storedAt(options: List<StoredOption>, index: Int): String? = options.getOrNull(index)?.stored

    fun defaultEndpoint(backend: String): String = when (backend) {
        BACKEND_ASR -> DEFAULT_ENDPOINT_ASR
        BACKEND_NIM -> DEFAULT_ENDPOINT_NIM
        else -> DEFAULT_ENDPOINT_OPENAI
    }

    /** Only the OpenAI-compatible service has a model field; the others are blank. */
    fun defaultModel(backend: String): String =
        if (backend == BACKEND_OPENAI) DEFAULT_MODEL_OPENAI else ""

    /**
     * The endpoint to use after switching to [newBackend]: the current one is
     * kept unless it is blank or still the default of ANOTHER backend, in
     * which case it becomes the new backend's default.
     */
    fun endpointAfterSwitch(newBackend: String, current: String): String {
        val others = listOf(BACKEND_OPENAI, BACKEND_ASR, BACKEND_NIM)
            .filter { it != newBackend }
            .map { defaultEndpoint(it) }
        return if (current.isBlank() || current.trim() in others) defaultEndpoint(newBackend) else current
    }

    /** Same rule for the model field. */
    fun modelAfterSwitch(newBackend: String, current: String): String {
        val others = listOf(BACKEND_OPENAI, BACKEND_ASR, BACKEND_NIM)
            .filter { it != newBackend }
            .map { defaultModel(it) }
            .filter { it.isNotEmpty() }
        return if (current.isBlank() || current.trim() in others) defaultModel(newBackend) else current
    }

    /** The language to keep after switching backends ("multi" belongs to NIM only). */
    fun languageAfterSwitch(newBackend: String, current: String): String = when {
        newBackend == BACKEND_NIM && (current.isBlank() || current == LanguageSettings.AUTO) -> LANGUAGE_MULTI
        newBackend != BACKEND_NIM && current == LANGUAGE_MULTI -> LanguageSettings.AUTO
        else -> current
    }
}
