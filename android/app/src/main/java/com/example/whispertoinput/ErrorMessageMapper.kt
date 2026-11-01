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
 * What the keyboard shows for one failure: [text] is the short status-bar
 * sentence, [detail] the full reason (TalkBack and the long-press tooltip),
 * [rateLimited] asks for the "請稍後再試" hint under the mic (HTTP 429).
 */
data class ErrorDisplay(val text: String, val detail: String? = null, val rateLimited: Boolean = false)

/**
 * One failure as string resources: the stage prefix wrapped around
 * [reasonRes] (short, status bar) or [fullReasonRes] (full, tooltip).
 * [code] is the HTTP status when there was one; it is the argument of
 * any reason string that has a placeholder.
 */
data class ErrorText(
    val stage: ErrorStage,
    @StringRes val reasonRes: Int,
    val code: Int? = null,
    @StringRes val fullReasonRes: Int = reasonRes,
    val retryAfterSeconds: Int? = null
) {
    fun resolve(context: Context): String =
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            context.getString(reasonRes, retryAfterSeconds)
        } else {
            context.getString(stage.prefixRes, reason(context, reasonRes))
        }

    fun resolveFull(context: Context): String =
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            context.getString(fullReasonRes, retryAfterSeconds)
        } else {
            context.getString(stage.prefixRes, reason(context, fullReasonRes))
        }

    fun display(context: Context): ErrorDisplay =
        ErrorDisplay(
            resolve(context),
            resolveFull(context),
            reasonRes == R.string.kb_err_rate_limited || reasonRes == R.string.kb_err_rate_limited_retry_after
        )

    private fun reason(context: Context, res: Int): String =
        if (retryAfterSeconds != null) context.getString(res, retryAfterSeconds)
        else if (code != null) context.getString(res, code)
        else context.getString(res)
}

/** Pure mapping from "what went wrong" to the sentence shown on the keyboard (no Android needed). */
object ErrorMessageMapper {
    fun fromHttp(stage: ErrorStage, code: Int, retryAfterSeconds: Int? = null): ErrorText = when {
        code == 401 || code == 403 -> ErrorText(stage, R.string.kb_err_invalid_key, code, R.string.kb_err_full_invalid_key)
        code == 429 -> {
            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                ErrorText(
                    stage,
                    R.string.kb_err_rate_limited_retry_after,
                    code,
                    R.string.kb_err_full_rate_limited_retry_after,
                    retryAfterSeconds
                )
            } else {
                ErrorText(stage, R.string.kb_err_rate_limited, code, R.string.kb_err_full_rate_limited)
            }
        }
        code in 500..599 -> ErrorText(stage, R.string.kb_err_server, code, R.string.kb_err_full_server)
        else -> ErrorText(stage, R.string.kb_err_http_code, code, R.string.kb_err_full_http_code)
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
