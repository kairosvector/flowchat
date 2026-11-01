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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a connection test failed (the settings page turns each into a message). */
enum class FailureKind { INVALID_KEY, MODEL_NOT_FOUND, UNREACHABLE, RATE_LIMITED, OTHER }

sealed class TestResult {
    /** [model] is the model that was found in the service's list, when the test looked for one. */
    data class Ok(val elapsedMs: Long, val model: String?) : TestResult()

    /** [code] is the HTTP status when there was one; [detail] never contains an API key. */
    data class Failed(val kind: FailureKind, val code: Int?, val detail: String) : TestResult()
}

/** Pure helpers behind the "測試連線" button: easy to unit test, no Android needed. */
object ConnectionTestMapper {
    private const val DETAIL_LIMIT = 120

    /** Maps a non-successful HTTP answer to a failure. [model] blank means "no model to look for". */
    fun fromHttp(code: Int, body: String, model: String, secrets: List<String>): TestResult.Failed {
        val kind = when {
            code == 401 || code == 403 -> FailureKind.INVALID_KEY
            code == 404 && model.isNotBlank() -> FailureKind.MODEL_NOT_FOUND
            code == 429 -> FailureKind.RATE_LIMITED
            else -> FailureKind.OTHER
        }
        return TestResult.Failed(kind, code, redact(body, secrets).replace('\n', ' ').trim().take(DETAIL_LIMIT))
    }

    /** The service answered, but its model list does not contain [model]. */
    fun modelMissing(model: String): TestResult.Failed =
        TestResult.Failed(FailureKind.MODEL_NOT_FOUND, null, model)

    /** Timeouts, DNS failures, refused connections and every other I/O problem: the server is not reachable. */
    fun fromException(e: Throwable, host: String): TestResult.Failed {
        val reason = when (e) {
            is UnknownHostException -> "DNS"
            is java.io.InterruptedIOException -> "timeout"
            else -> e.javaClass.simpleName
        }
        return TestResult.Failed(FailureKind.UNREACHABLE, null, host.ifBlank { reason })
    }

    /** True when the OpenAI-style `{"data":[{"id":"..."}]}` model list contains [model]. */
    fun modelListed(body: String, model: String): Boolean =
        model.isNotBlank() && Regex("\"id\"\\s*:\\s*\"" + Regex.escape(model) + "\"").containsMatchIn(body)

    /**
     * Removes the API keys (and anything shaped like a bearer token / Groq or
     * OpenAI key) from text that may be shown or logged.
     */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (secret in secrets) {
            if (secret.isNotBlank()) out = out.replace(secret, "••••")
        }
        out = out.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer ••••")
        out = out.replace(Regex("\\b(gsk|sk)[-_][A-Za-z0-9_-]{8,}"), "••••")
        return out
    }

    /** `https://host/openai/v1/audio/transcriptions` -> `https://host/openai/v1` (query dropped). */
    fun baseUrl(endpoint: String, suffix: String): String {
        val noQuery = endpoint.trim().substringBefore('?').trimEnd('/')
        return noQuery.removeSuffix(suffix).trimEnd('/')
    }

    fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()
}

/** Runs the actual requests; cancellable (cancelling the coroutine cancels the call). */
class ConnectionTester(private val context: Context) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Speech recognition. [secrets] are only used to scrub the text that comes back. */
    suspend fun testStt(
        backend: String,
        endpoint: String,
        apiKey: String,
        model: String,
        language: String
    ): TestResult {
        val secrets = listOf(apiKey)
        val host = ConnectionTestMapper.hostOf(endpoint)
        val started = System.currentTimeMillis()
        return try {
            if (backend == SettingsOptions.BACKEND_OPENAI) {
                val base = ConnectionTestMapper.baseUrl(endpoint, "/audio/transcriptions")
                val listed = listModels(base, apiKey, model, secrets, started)
                // Some compatible servers have no /models route: fall back to
                // sending one second of silence to the real endpoint.
                if (listed is TestResult.Failed && listed.code == 404 && listed.kind == FailureKind.MODEL_NOT_FOUND) {
                    postSilence(backend, endpoint, apiKey, model, language, secrets, started)
                } else {
                    listed
                }
            } else {
                postSilence(backend, endpoint, apiKey, model, language, secrets, started)
            }
        } catch (e: IOException) {
            ConnectionTestMapper.fromException(e, host)
        } catch (e: IllegalArgumentException) {
            TestResult.Failed(FailureKind.OTHER, null, "bad URL")
        }
    }

    /** AI polish: the model must be in the service's list. */
    suspend fun testLlm(endpoint: String, apiKey: String, model: String): TestResult {
        val host = ConnectionTestMapper.hostOf(endpoint)
        val started = System.currentTimeMillis()
        return try {
            val base = ConnectionTestMapper.baseUrl(endpoint, "/chat/completions")
            listModels(base, apiKey, model, listOf(apiKey), started)
        } catch (e: IOException) {
            ConnectionTestMapper.fromException(e, host)
        } catch (e: IllegalArgumentException) {
            TestResult.Failed(FailureKind.OTHER, null, "bad URL")
        }
    }

    private suspend fun listModels(
        base: String, apiKey: String, model: String, secrets: List<String>, started: Long
    ): TestResult {
        val request = Request.Builder()
            .url("$base/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // A 404 here only says "no /models route" (the model fallback is the caller's job).
                return ConnectionTestMapper.fromHttp(response.code, body, model.ifBlank { "?" }, secrets)
            }
            return if (ConnectionTestMapper.modelListed(body, model)) {
                TestResult.Ok(System.currentTimeMillis() - started, model)
            } else {
                ConnectionTestMapper.modelMissing(model)
            }
        }
    }

    private suspend fun postSilence(
        backend: String, endpoint: String, apiKey: String, model: String, language: String,
        secrets: List<String>, started: Long
    ): TestResult {
        val nim = backend == SettingsOptions.BACKEND_NIM
        val assetName = if (nim) "test_silence.ogg" else "test_silence.m4a"
        val mediaType = if (nim) "audio/ogg" else "audio/mp4"
        val audio = context.assets.open(assetName).use { it.readBytes() }
        val fileBody = audio.toRequestBody(mediaType.toMediaTypeOrNull())
        val fileName = if (nim) "@audio.ogg" else "@audio.m4a"
        val lang = LanguageSettings.normalize(language)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            when (backend) {
                SettingsOptions.BACKEND_ASR -> addFormDataPart("audio_file", fileName, fileBody)
                else -> addFormDataPart("file", fileName, fileBody)
            }
            if (backend == SettingsOptions.BACKEND_OPENAI) {
                addFormDataPart("model", model)
                addFormDataPart("response_format", "text")
                if (lang != LanguageSettings.AUTO) addFormDataPart("language", lang)
            }
            if (nim) {
                addFormDataPart("language", language.ifBlank { SettingsOptions.LANGUAGE_MULTI })
                addFormDataPart("response_format", "text")
            }
        }.build()
        val url = when (backend) {
            SettingsOptions.BACKEND_OPENAI, SettingsOptions.BACKEND_ASR ->
                "$endpoint?encode=true&task=transcribe&language=$lang&word_timestamps=false&output=txt"
            else -> endpoint
        }
        val builder = Request.Builder().url(url).post(form)
        if (backend == SettingsOptions.BACKEND_OPENAI) builder.header("Authorization", "Bearer $apiKey")
        client.newCall(builder.build()).await().use { response ->
            val body = response.body?.string().orEmpty()
            return if (response.isSuccessful) {
                TestResult.Ok(System.currentTimeMillis() - started, null)
            } else {
                ConnectionTestMapper.fromHttp(response.code, body, model, secrets)
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) response.close() else cont.resume(response)
            }
        })
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
