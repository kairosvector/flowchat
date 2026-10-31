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
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import com.github.liuyueyi.quick.transfer.ChineseUtils

/** Stages reported to the keyboard panel while polishing (fork2). */
enum class PolishStage {
    /** STT finished: show the raw text first. */
    RAW,
    /** An LLM polish call is in flight: show "正在潤飾中…". */
    POLISHING
}

/**
 * What happened to polishing for one result (fork6 round 4), so the
 * result card never silently shows only the raw text.
 */
enum class PolishNote {
    /** Polishing is switched off (or there was nothing to polish). */
    OFF,
    /** Fewer than [POLISH_MIN_COUNTED_CHARS] counted characters; polishing was skipped by design. */
    TOO_SHORT,
    /** The LLM call failed (HTTP error, timeout, empty reply ...). */
    FAILED,
    /** Polished successfully. */
    OK
}

class WhisperTranscriber {
    private data class Config(
        val endpoint: String,
        val languageCode: String,
        val speechToTextBackend: String,
        val apiKey: String,
        val model: String,
        val postprocessing: String,
        val addTrailingSpace: Boolean,
        val polishEnabled: Boolean,
        val llmEndpoint: String,
        val llmModel: String,
        val llmApiKey: String,
        val llmPrompt: String
    )

    /**
     * [textWithAttach] is what gets inserted (final text plus the
     * space/newline suffix from the space/enter key, if any).
     * [rawCore] is the post-processed transcription before polishing;
     * [polishedCore] is null when polishing was skipped or failed.
     */
    private data class Outcome(
        val textWithAttach: String,
        val rawCore: String,
        val polishedCore: String?,
        val polishNote: PolishNote,
        val polishError: ErrorText?
    )

    private val TAG = "WhisperTranscriber"
    private var currentTranscriptionJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (text: String?, raw: String?, polished: String?, note: PolishNote, polishError: ErrorDisplay?) -> Unit,
        exceptionCallback: (ErrorDisplay) -> Unit,
        stageCallback: ((PolishStage, String?) -> Unit)? = null
    ) {
        suspend fun makeWhisperRequest(): Outcome {
            // Retrieve configs
            val (endpoint, languageCode, speechToTextBackend, apiKey, model, postprocessing, addTrailingSpace, polishEnabled, llmEndpoint, llmModel, llmApiKey, llmPrompt) = context.dataStore.data.map { preferences: Preferences ->
                Config(
                    // Fork2: read fallbacks are Groq + Whisper, so a
                    // fresh install that never opened settings still
                    // points at Groq. These fallbacks never write
                    // anything, so they cannot overwrite saved values.
                    preferences[ENDPOINT] ?: DEFAULT_STT_ENDPOINT,
                    preferences[LANGUAGE_CODE] ?: DEFAULT_STT_LANGUAGE,
                    preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
                    preferences[API_KEY] ?: "",
                    preferences[MODEL] ?: DEFAULT_STT_MODEL,
                    preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
                    preferences[ADD_TRAILING_SPACE] ?: false,
                    preferences[POLISH_ENABLED] ?: true,
                    preferences[LLM_ENDPOINT] ?: DEFAULT_LLM_ENDPOINT,
                    preferences[LLM_MODEL] ?: DEFAULT_LLM_MODEL,
                    preferences[LLM_API_KEY] ?: "",
                    preferences[LLM_PROMPT] ?: DEFAULT_POLISH_PROMPT
                )
            }.first()

            // Foolproof message
            if (endpoint == "") {
                throw SettingsMissingException(context.getString(R.string.error_endpoint_unset))
            }

            // Make request
            val client = OkHttpClient()
            val request = buildWhisperRequest(
                context,
                filename,
                mediaType,
                speechToTextBackend,
                endpoint,
                languageCode,
                apiKey,
                model
            )
            val response = client.newCall(request).execute()

            // If request is not successful, or response code is weird
            // Only the status code is kept: the body is dropped unread.
            if (!response.isSuccessful || response.code / 100 != 2) {
                val code = response.code
                response.close()
                throw HttpStatusException(code)
            }

            var rawText = response.body!!.string().trim()
            
            // For NVIDIA NIM, remove quotes if they wrap the text
            // Not sure if this is a bug or a feature...
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) && 
                rawText.startsWith("\"") && rawText.endsWith("\"")) {
                rawText = rawText.substring(1, rawText.length - 1).trim()
            }
            
            val coreText = when (postprocessing) {
                context.getString(R.string.settings_option_to_simplified) -> ChineseUtils.tw2s(rawText)
                context.getString(R.string.settings_option_to_traditional) -> ChineseUtils.s2tw(rawText)
                else -> rawText // No conversion
            }.trim()

            // Fork2 staged polish: show the raw text first, then (only
            // for long enough text with polishing enabled) show the
            // polishing state while the LLM call runs. Insertion happens
            // exactly once, at the end, with the polished text if it
            // succeeded and the raw text otherwise — never insert raw
            // first and replace it later, because the user may already
            // have moved the cursor or edited the text while waiting.
            val trimmedCore = coreText.trim()
            stageCallback?.invoke(PolishStage.RAW, trimmedCore)
            var polishedCore: String? = null
            var polishNote = PolishNote.OFF
            var polishError: ErrorText? = null
            val countedChars = countPolishChars(trimmedCore)
            if (polishEnabled && countedChars >= POLISH_MIN_COUNTED_CHARS) {
                stageCallback?.invoke(PolishStage.POLISHING, null)
                val llmKey = llmApiKey.ifBlank { apiKey }
                val polishResult = PolishClient.polishDetailed(
                    trimmedCore,
                    PolishConfig(
                        enabled = true,
                        endpoint = llmEndpoint,
                        model = llmModel,
                        apiKey = llmKey,
                        prompt = llmPrompt
                    )
                )
                when (polishResult) {
                    is PolishClient.Result.Ok -> {
                        polishedCore = polishResult.text
                        polishNote = PolishNote.OK
                    }
                    is PolishClient.Result.Failed -> {
                        polishError = polishResult.error
                        polishNote = PolishNote.FAILED
                    }
                }
            } else if (polishEnabled) {
                Log.i(
                    TAG,
                    "polish skipped: $countedChars counted chars (punctuation/whitespace excluded), " +
                        "minimum is $POLISH_MIN_COUNTED_CHARS"
                )
                polishNote = PolishNote.TOO_SHORT
            }
            val finalCore = polishedCore ?: trimmedCore
            val textWithAttach = if (attachToEnd == "") {
                finalCore + if (addTrailingSpace) " " else ""
            } else {
                // Only used for space key and enter key.
                finalCore + attachToEnd
            }
            return Outcome(textWithAttach, trimmedCore, polishedCore, polishNote, polishError)
        }

        // Create a cancellable job in the main thread (for UI updating)
        val job = CoroutineScope(Dispatchers.Main).launch {

            // Within the job, make a suspend call at the I/O thread
            // It suspends before result is obtained.
            // Returns (outcome, the exception that ended the request)
            val (outcome, failure) = withContext(Dispatchers.IO) {
                try {
                    // Perform transcription (and polish, if applicable) here.
                    val outcome = makeWhisperRequest()
                    // Fork change: record the inserted text for later review.
                    // Best-effort only (TranscriptionHistory.add never throws);
                    // a storage failure must not affect insertion below.
                    TranscriptionHistory.add(
                        context,
                        outcome.rawCore,
                        outcome.polishedCore
                    )
                    // Clean up unused audio file after transcription
                    // Ref: https://developer.android.com/reference/android/media/MediaRecorder#setOutputFile(java.io.File)
                    File(filename).delete()
                    return@withContext Pair<Outcome?, Exception?>(outcome, null)
                } catch (e: CancellationException) {
                    // Task was canceled
                    return@withContext Pair<Outcome?, Exception?>(null, null)
                } catch (e: Exception) {
                    return@withContext Pair<Outcome?, Exception?>(null, e)
                }
            }

            // This callback is within the main thread. Raw and polished
            // come straight from the outcome (fork6), so the result card
            // never depends on the history file having been written.
            callback.invoke(
                outcome?.textWithAttach,
                outcome?.rawCore,
                outcome?.polishedCore,
                outcome?.polishNote ?: PolishNote.OFF,
                outcome?.polishError?.display(context)
            )

            // A short sentence for the status bar. The exception message
            // (which could hold a response body) is never shown or logged;
            // only the missing-setting hints are already short and are kept.
            if (failure != null) {
                Log.e(TAG, ErrorMessageMapper.logLine(ErrorStage.TRANSCRIBE, failure))
                val hint = (failure as? SettingsMissingException)?.message
                exceptionCallback(
                    if (!hint.isNullOrEmpty()) {
                        ErrorDisplay(hint)
                    } else {
                        ErrorMessageMapper.fromThrowable(ErrorStage.TRANSCRIBE, failure).display(context)
                    }
                )
            }
        }

        registerTranscriptionJob(job)
    }

    /**
     * Fork6: polish an already transcribed text again (the "re-polish"
     * action on the result card). Uses the same polish settings as the
     * normal pipeline but ignores the on/off switch and the short-text
     * threshold, because the user asked for it explicitly. [callback]
     * runs on the main thread with the polished text, or null when
     * polishing failed or was cancelled; on failure the second argument
     * is what to show ("潤飾失敗：…" and its full reason). On success the last record is
     * updated, so the keyboard and the history keep agreeing.
     * Cancel with [stop], like a normal transcription.
     */
    fun polishAsync(context: Context, rawText: String, callback: (String?, ErrorDisplay?) -> Unit) {
        val job = CoroutineScope(Dispatchers.Main).launch {
            val (polished, error) = withContext(Dispatchers.IO) {
                try {
                    val prefs = context.dataStore.data.first()
                    val sttKey = prefs[API_KEY] ?: ""
                    val result = PolishClient.polishDetailed(
                        rawText,
                        PolishConfig(
                            enabled = true,
                            endpoint = prefs[LLM_ENDPOINT] ?: DEFAULT_LLM_ENDPOINT,
                            model = prefs[LLM_MODEL] ?: DEFAULT_LLM_MODEL,
                            apiKey = (prefs[LLM_API_KEY] ?: "").ifBlank { sttKey },
                            prompt = prefs[LLM_PROMPT] ?: DEFAULT_POLISH_PROMPT
                        )
                    )
                    when (result) {
                        is PolishClient.Result.Ok -> {
                            TranscriptionHistory.add(context, rawText, result.text)
                            Pair<String?, ErrorText?>(result.text, null)
                        }
                        is PolishClient.Result.Failed -> Pair<String?, ErrorText?>(null, result.error)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, ErrorMessageMapper.logLine(ErrorStage.POLISH, e))
                    Pair<String?, ErrorText?>(null, ErrorMessageMapper.fromThrowable(ErrorStage.POLISH, e))
                }
            }
            callback(polished, error?.display(context))
        }
        registerTranscriptionJob(job)
    }

    fun stop() {
        registerTranscriptionJob(null)
    }

    private fun registerTranscriptionJob(job: Job?) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = job
    }

    private fun buildWhisperRequest(
        context: Context,
        filename: String,
        mediaType: String,
        speechToTextBackend: String,
        endpoint: String,
        languageCode: String,
        apiKey: String,
        model: String
    ): Request {
        // Please refer to the following for the endpoint/payload definitions:
        // OpenAI API:
        // - https://platform.openai.com/docs/api-reference/audio/createTranscription
        // - https://platform.openai.com/docs/api-reference/making-requests
        // Whisper ASR WebService:
        // - https://ahmetoner.com/whisper-asr-webservice/run/#usage
        // NVIDIA NIM:
        // - No public documentation for HTTP-style requests.
        // - Source code at `/opt/nim/inference.py` in docker container `nvcr.io/nim/nvidia/riva-asr:1.3.0`.
        /*
            ...
            @HttpNIMApiInterface.route('/v1/audio/transcriptions', methods=["post"])
            async def transcriptions(
                self,
                file: UploadFile = File(...),
                model: Optional[str] = Form(None),
                language: Optional[str] = Form(None),
                prompt: Optional[str] = Form(None),
                response_format: Optional[str] = Form(None),
                temperature: Optional[float] = Form(None),
            ):
            ...
         */
        val file: File = File(filename)
        val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
        val requestBody: RequestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            // Determine filename based on media type
            val formDataFilename = if (mediaType == "audio/ogg") "@audio.ogg" else "@audio.m4a"
            
            // Add file to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api) || 
                speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("file", formDataFilename, fileBody)
            } else if (speechToTextBackend == context.getString(R.string.settings_option_whisper_asr_webservice)) {
                addFormDataPart("audio_file", formDataFilename, fileBody)
            }
            // Add backend-specific parameters to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                addFormDataPart("model", model)
                addFormDataPart("response_format", "text")
                // Fork6: the keyboard's language key edits this setting, so
                // a fixed language has to reach OpenAI-compatible backends
                // (Groq ignores the URL query). "auto" stays unset so the
                // default request is unchanged.
                // Only a plain 2-3 letter code is sent ("zh-TW" -> "zh");
                // "multi", blanks and anything odd stay unset (= auto).
                val language = LanguageSettings.normalize(languageCode)
                if (language != LanguageSettings.AUTO) {
                    addFormDataPart("language", language)
                }
            }
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("language", languageCode)
                addFormDataPart("response_format", "text")
            }
        }.build()

        val requestHeaders: Headers = Headers.Builder().apply {
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                // Foolproof message
                if (apiKey == "") {
                    throw SettingsMissingException(context.getString(R.string.error_apikey_unset))
                }
                add("Authorization", "Bearer $apiKey")
            }
            add("Content-Type", "multipart/form-data")
        }.build()

        // Build URL with endpoint-specific parameters
        val url = when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
            }
            else -> endpoint
        }

        return Request.Builder()
            .headers(requestHeaders)
            .url(url)
            .post(requestBody)
            .build()
    }
}
