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

/**
 * Extracts a one-line preview of a tone prompt for the settings list (SPEC_r8 §20.3, P1-3).
 * If "語氣：" is found in the prompt, extraction starts from there with "…" prepended.
 * If not found, extraction starts from the beginning without "…".
 * Consecutive whitespace and newlines are collapsed to a single space.
 */
object TonePreview {
    fun of(prompt: String): String {
        val normalized = prompt.replace(Regex("\\s+"), " ").trim()
        val idx = normalized.indexOf("語氣：")
        return if (idx >= 0) {
            "…" + normalized.substring(idx)
        } else {
            normalized
        }
    }
}
