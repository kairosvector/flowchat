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
import androidx.annotation.StringRes
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Which step of the pipeline failed; picks the "轉寫失敗：" / "潤飾失敗：" prefix. */
enum class ErrorStage(@StringRes val prefixRes: Int, val logName: String) {
    TRANSCRIBE(R.string.kb_err_stage_transcribe, "transcribe"),
    POLISH(R.string.kb_err_stage_polish, "polish")
}

/**
 * A non-2xx answer from a server. Carries only the status code: the
 * response body (which may echo the request, or contain account details)
 * is dropped where the answer is read, so it can never reach the UI or the log.
 */
class HttpStatusException(val code: Int) : Exception("HTTP $code")

/** A setting the request needs is empty; the message is the (already short) settings hint. */
class SettingsMissingException(message: String) : Exception(message)

/**
 * The short status-bar sentence for one failure, as string resources:
 * the stage prefix wrapped around [reasonRes]. [code] is only filled for
 * "other HTTP code" and is the argument of that reason string.
 */
data class ErrorText(val stage: ErrorStage, @StringRes val reasonRes: Int, val code: Int? = null) {
    fun resolve(context: Context): String {
        val reason = if (code != null) context.getString(reasonRes, code) else context.getString(reasonRes)
        return context.getString(stage.prefixRes, reason)
    }
}

/** Pure mapping from "what went wrong" to the sentence shown on the keyboard (no Android needed). */
object ErrorMessageMapper {
    fun fromHttp(stage: ErrorStage, code: Int): ErrorText = when {
        code == 401 || code == 403 -> ErrorText(stage, R.string.kb_err_invalid_key)
        code == 429 -> ErrorText(stage, R.string.kb_err_rate_limited)
        code in 500..599 -> ErrorText(stage, R.string.kb_err_server)
        else -> ErrorText(stage, R.string.kb_err_http_code, code)
    }

    /**
     * Only the type of [e] decides the sentence; its message is never read,
     * because a message may contain a response body or a key.
     */
    fun fromThrowable(stage: ErrorStage, e: Throwable): ErrorText = when (e) {
        is HttpStatusException -> fromHttp(stage, e.code)
        // SocketTimeoutException is an InterruptedIOException too.
        is InterruptedIOException -> ErrorText(stage, R.string.kb_err_timeout)
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            ErrorText(stage, R.string.kb_err_offline)
        else -> other(stage)
    }

    fun other(stage: ErrorStage): ErrorText = ErrorText(stage, R.string.kb_err_other)

    /** The de-identified log line: stage and HTTP code or exception class name, nothing else. */
    fun logLine(stage: ErrorStage, e: Throwable): String = when (e) {
        is HttpStatusException -> "${stage.logName} failed: HTTP ${e.code}"
        is SettingsMissingException -> "${stage.logName} failed: setting missing"
        else -> "${stage.logName} failed: ${e.javaClass.simpleName}"
    }
}
