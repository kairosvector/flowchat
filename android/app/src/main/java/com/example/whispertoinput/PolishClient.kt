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
 * Fork change (2026-10-30, fork2): LLM polish step for the
 * transcription pipeline. This client is deliberately small and
 * failure-tolerant: any failure returns null and the caller falls
 * back to the raw transcription, so a polish failure can never lose
 * or block the user's text. No API key is stored here; the key comes
 * from the user's settings on the device at call time.
 */

package com.example.whispertoinput

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Default short-text skip threshold.
 *
 * This number is a fork default chosen by the implementer, NOT a
 * number kevy set: kevy's rule is only "too-short text skips polish",
 * with no threshold specified. If kevy later picks a number, change
 * this single constant (and the CHANGES.md note) and nothing else.
 * Comparison is on the trimmed raw text length in characters.
 */
const val POLISH_MIN_LENGTH = 10

const val DEFAULT_LLM_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
const val DEFAULT_LLM_MODEL = "qwen/qwen3.8-27b"

/** Fork2 STT defaults (Groq + Whisper), used only as read fallbacks. */
const val DEFAULT_STT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
const val DEFAULT_STT_LANGUAGE = "auto"

/** Default polish prompt (kevy's established Traditional Chinese prompt). */
const val DEFAULT_POLISH_PROMPT =
    "你是文字編輯，只輸出整理後的成品，不要加任何解釋、前言或引號。" +
    "規則：刪除贅字（嗯、呃、那個、就是說），修正文法、補上標點；" +
    "講者自我修正時只保留最後版本，例如『約明天下午、不對、是後天』只留『後天』；" +
    "全文必須是繁體中文，絕對不要輸出簡體字，逐字稿是簡體也要轉成繁體；" +
    "數字、英文術語保持原樣；不要擴寫、不要腦補、不要改變原意；" +
    "非中文內容保持原語言，不要硬翻成中文。"

data class PolishConfig(
    val enabled: Boolean,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val prompt: String
)

object PolishClient {
    private const val TEMPERATURE = 0.2

    /**
     * Sends [rawText] to the LLM for polishing.
     * Returns the cleaned text, or null on any failure (timeout, HTTP
     * error, unknown model, empty or unwrappable response). Callers
     * must treat null as "use the raw text".
     * This is a blocking call; callers must run it off the main thread.
     */
    fun polish(rawText: String, config: PolishConfig): String? {
        return try {
            val endpoint = config.endpoint.trim()
            val model = config.model.trim()
            val key = config.apiKey.trim()
            if (endpoint.isEmpty() || model.isEmpty() || key.isEmpty()) return null
            val prompt = config.prompt.ifBlank { DEFAULT_POLISH_PROMPT }

            val body = JSONObject()
            body.put("model", model)
            body.put("temperature", TEMPERATURE)
            val messages = JSONArray()
            val system = JSONObject()
            system.put("role", "system")
            system.put("content", prompt)
            messages.put(system)
            val user = JSONObject()
            user.put("role", "user")
            user.put("content", rawText)
            messages.put(user)
            body.put("messages", messages)

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: return null
            unwrap(content)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strips a surrounding markdown fence and/or surrounding double
     * quotes, if the whole response is wrapped in them. Returns null
     * if nothing usable remains.
     */
    internal fun unwrap(content: String): String? {
        var text = content.trim()
        if (text.isEmpty()) return null
        // Markdown fence: ``` ... ``` (optionally with a language tag).
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline < 0) return null
            text = text.substring(firstNewline + 1)
            val fence = text.lastIndexOf("```")
            if (fence < 0) return null
            text = text.substring(0, fence).trim()
        }
        // Surrounding double quotes only when both ends match.
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text.ifEmpty { null }
    }
}
