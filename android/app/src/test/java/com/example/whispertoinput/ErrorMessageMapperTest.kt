package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Rounds 9 and 10: the short status-bar sentence and the full reason for each failure (fake keys only). */
class ErrorMessageMapperTest {
    private val stages = listOf(ErrorStage.TRANSCRIBE, ErrorStage.POLISH)

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        Regex("<string name=\"([^\"]+)\"[^>]*>([^<]*)</string>")
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val names = mapOf(
        R.string.kb_err_stage_transcribe to "kb_err_stage_transcribe",
        R.string.kb_err_stage_polish to "kb_err_stage_polish",
        R.string.kb_err_invalid_key to "kb_err_invalid_key",
        R.string.kb_err_rate_limited to "kb_err_rate_limited",
        R.string.kb_err_server to "kb_err_server",
        R.string.kb_err_timeout to "kb_err_timeout",
        R.string.kb_err_offline to "kb_err_offline",
        R.string.kb_err_http_code to "kb_err_http_code",
        R.string.kb_err_other to "kb_err_other",
        R.string.kb_err_full_invalid_key to "kb_err_full_invalid_key",
        R.string.kb_err_full_rate_limited to "kb_err_full_rate_limited",
        R.string.kb_err_full_server to "kb_err_full_server",
        R.string.kb_err_full_http_code to "kb_err_full_http_code"
    )

    private fun wrap(stage: ErrorStage, reasonRes: Int, code: Int?): String {
        val template = strings.getValue(names.getValue(reasonRes))
        val reason = if (code != null) template.replace("%1\$d", code.toString()) else template
        return strings.getValue(names.getValue(stage.prefixRes)).replace("%1\$s", reason)
    }

    /** What the status bar shows: the stage prefix around the short reason, from strings.xml. */
    private fun sentence(e: ErrorText) = wrap(e.stage, e.reasonRes, e.code)

    /** What the tooltip / TalkBack get: the stage prefix around the full reason. */
    private fun full(e: ErrorText) = wrap(e.stage, e.fullReasonRes, e.code)

    private fun http(stage: ErrorStage, code: Int) = ErrorMessageMapper.fromHttp(stage, code)
    private fun thrown(stage: ErrorStage, e: Throwable) = ErrorMessageMapper.fromThrowable(stage, e)

    /** Width in full-width characters: ASCII (digits, letters, space) counts half, everything else one. */
    private fun width(text: String): Double = text.sumOf { if (it.code < 0x80) 0.5 else 1.0 }

    @Test
    fun http401And403MeanInvalidKey() {
        for (stage in stages) for (code in listOf(401, 403)) {
            val t = http(stage, code)
            assertEquals(R.string.kb_err_invalid_key, t.reasonRes)
            assertEquals(R.string.kb_err_full_invalid_key, t.fullReasonRes)
            assertEquals(code, t.code)
        }
    }

    @Test
    fun http429MeansRateLimited() {
        for (stage in stages) {
            val t = http(stage, 429)
            assertEquals(R.string.kb_err_rate_limited, t.reasonRes)
            assertEquals(R.string.kb_err_full_rate_limited, t.fullReasonRes)
        }
    }

    @Test
    fun http500To599MeanServerError() {
        for (stage in stages) for (code in listOf(500, 503, 599)) {
            val t = http(stage, code)
            assertEquals(R.string.kb_err_server, t.reasonRes)
            assertEquals(R.string.kb_err_full_server, t.fullReasonRes)
            assertEquals(code, t.code)
        }
    }

    @Test
    fun otherHttpCodesShowTheCode() {
        for (stage in stages) for (code in listOf(404, 418)) {
            val t = http(stage, code)
            assertEquals(R.string.kb_err_http_code, t.reasonRes)
            assertEquals(R.string.kb_err_full_http_code, t.fullReasonRes)
            assertEquals(code, t.code)
        }
    }

    @Test
    fun theBoundariesOfTheServerRangeAreExact() {
        assertEquals(R.string.kb_err_http_code, http(ErrorStage.TRANSCRIBE, 499).reasonRes)
        assertEquals(R.string.kb_err_http_code, http(ErrorStage.TRANSCRIBE, 600).reasonRes)
        assertEquals(R.string.kb_err_server, http(ErrorStage.TRANSCRIBE, 500).reasonRes)
    }

    @Test
    fun timeoutsMeanConnectionTimeout() {
        for (stage in stages) {
            assertEquals(R.string.kb_err_timeout, thrown(stage, SocketTimeoutException("read timed out")).reasonRes)
            assertEquals(R.string.kb_err_timeout, thrown(stage, java.io.InterruptedIOException("timeout")).reasonRes)
        }
    }

    @Test
    fun unreachableHostsMeanNoNetwork() {
        for (stage in stages) {
            assertEquals(R.string.kb_err_offline, thrown(stage, UnknownHostException("api.groq.com")).reasonRes)
            assertEquals(R.string.kb_err_offline, thrown(stage, ConnectException("Failed to connect")).reasonRes)
            assertEquals(R.string.kb_err_offline, thrown(stage, NoRouteToHostException("no route")).reasonRes)
        }
    }

    @Test
    fun otherIoAndPlainExceptionsAreJustAnError() {
        for (stage in stages) {
            for (e in listOf<Throwable>(IOException("boom"), Exception("boom"), IllegalArgumentException("bad url"))) {
                val t = thrown(stage, e)
                assertEquals(R.string.kb_err_other, t.reasonRes)
                assertEquals(R.string.kb_err_other, t.fullReasonRes)
                assertNull(t.code)
            }
        }
    }

    @Test
    fun anHttpStatusExceptionGoesThroughTheHttpTable() {
        assertEquals(R.string.kb_err_invalid_key, thrown(ErrorStage.POLISH, HttpStatusException(401)).reasonRes)
        val t = thrown(ErrorStage.TRANSCRIBE, HttpStatusException(418))
        assertEquals(R.string.kb_err_http_code, t.reasonRes)
        assertEquals(418, t.code)
    }

    @Test
    fun theStageDecidesThePrefix() {
        assertEquals(R.string.kb_err_stage_transcribe, http(ErrorStage.TRANSCRIBE, 401).stage.prefixRes)
        assertEquals(R.string.kb_err_stage_polish, http(ErrorStage.POLISH, 401).stage.prefixRes)
        assertEquals(R.string.kb_err_stage_polish, ErrorMessageMapper.other(ErrorStage.POLISH).stage.prefixRes)
    }

    @Test
    fun theShortSentencesReadAsSpecified() {
        assertEquals("轉寫失敗：金鑰無效", sentence(http(ErrorStage.TRANSCRIBE, 401)))
        assertEquals("轉寫失敗：金鑰無效", sentence(http(ErrorStage.TRANSCRIBE, 403)))
        assertEquals("潤飾失敗：金鑰無效", sentence(http(ErrorStage.POLISH, 401)))
        assertEquals("潤飾失敗：金鑰無效", sentence(http(ErrorStage.POLISH, 403)))
        assertEquals("轉寫失敗：次數超量", sentence(http(ErrorStage.TRANSCRIBE, 429)))
        assertEquals("潤飾失敗：次數超量", sentence(http(ErrorStage.POLISH, 429)))
        assertEquals("轉寫失敗：服務異常", sentence(http(ErrorStage.TRANSCRIBE, 500)))
        assertEquals("轉寫失敗：服務異常", sentence(http(ErrorStage.TRANSCRIBE, 503)))
        assertEquals("潤飾失敗：服務異常", sentence(http(ErrorStage.POLISH, 599)))
        assertEquals("轉寫失敗：連線逾時", sentence(thrown(ErrorStage.TRANSCRIBE, SocketTimeoutException())))
        assertEquals("潤飾失敗：連線逾時", sentence(thrown(ErrorStage.POLISH, SocketTimeoutException())))
        assertEquals("轉寫失敗：沒有網路", sentence(thrown(ErrorStage.TRANSCRIBE, UnknownHostException())))
        assertEquals("潤飾失敗：沒有網路", sentence(thrown(ErrorStage.POLISH, ConnectException())))
        assertEquals("轉寫失敗：錯誤 404", sentence(http(ErrorStage.TRANSCRIBE, 404)))
        assertEquals("轉寫失敗：錯誤 418", sentence(http(ErrorStage.TRANSCRIBE, 418)))
        assertEquals("潤飾失敗：錯誤 418", sentence(http(ErrorStage.POLISH, 418)))
        assertEquals("轉寫失敗：發生錯誤", sentence(thrown(ErrorStage.TRANSCRIBE, IOException("x"))))
        assertEquals("潤飾失敗：發生錯誤", sentence(ErrorMessageMapper.other(ErrorStage.POLISH)))
    }

    @Test
    fun theFullReasonsKeepTheLongWordingAndAddTheHttpCode() {
        assertEquals("轉寫失敗：API Key 無效（HTTP 401）", full(http(ErrorStage.TRANSCRIBE, 401)))
        assertEquals("潤飾失敗：API Key 無效（HTTP 403）", full(http(ErrorStage.POLISH, 403)))
        assertEquals("轉寫失敗：次數超量，請稍後再試（HTTP 429）", full(http(ErrorStage.TRANSCRIBE, 429)))
        assertEquals("潤飾失敗：次數超量，請稍後再試（HTTP 429）", full(http(ErrorStage.POLISH, 429)))
        assertEquals("潤飾失敗：伺服器錯誤（HTTP 503）", full(http(ErrorStage.POLISH, 503)))
        assertEquals("轉寫失敗：伺服器錯誤（HTTP 500）", full(http(ErrorStage.TRANSCRIBE, 500)))
        assertEquals("轉寫失敗：連線逾時", full(thrown(ErrorStage.TRANSCRIBE, SocketTimeoutException())))
        assertEquals("潤飾失敗：沒有網路", full(thrown(ErrorStage.POLISH, UnknownHostException())))
        assertEquals("轉寫失敗：發生錯誤（代碼 418）", full(http(ErrorStage.TRANSCRIBE, 418)))
        assertEquals("潤飾失敗：發生錯誤（代碼 404）", full(http(ErrorStage.POLISH, 404)))
        assertEquals("轉寫失敗：發生錯誤", full(thrown(ErrorStage.TRANSCRIBE, IOException("x"))))
    }

    @Test
    fun everyShortSentenceFitsNineFullWidthCharacters() {
        val all = mutableListOf<ErrorText>()
        for (stage in stages) {
            for (code in listOf(401, 403, 429, 500, 503, 599, 404, 418)) all += http(stage, code)
            all += thrown(stage, SocketTimeoutException())
            all += thrown(stage, UnknownHostException())
            all += thrown(stage, IOException("x"))
        }
        for (t in all) {
            val text = sentence(t)
            assertTrue("$text is ${width(text)} wide", width(text) <= 9.0)
        }
        // four-digit codes may run a little over; that is allowed to ellipsize
        assertTrue(width(sentence(http(ErrorStage.TRANSCRIBE, 1001))) > 9.0)
    }

    @Test
    fun theShortSentencesNeverContainTheWordsMovedToTheFullReason() {
        for (stage in stages) for (code in listOf(401, 429, 503, 418)) {
            val text = sentence(http(stage, code))
            assertFalse(text, text.contains("請稍後"))
            assertFalse(text, text.contains("HTTP"))
            assertFalse(text, text.contains("代碼"))
        }
    }

    @Test
    fun theHintStringsExistAndStayShort() {
        // 429: the hint row says only this (the retry key sits right next to it, so no ↻ in the text)
        assertEquals("請稍後再試", strings.getValue("kb_hint_rate_limited"))
        assertFalse(strings.containsKey("kb_hint_rate_limited_retry"))
    }

    @Test
    fun aResponseBodyAndAKeyNeverReachTheSentenceTheFullReasonOrTheLog() {
        val key = "gsk_test_xxxx3f9a"
        val body = "{\"error\":{\"message\":\"Invalid API Key $key\",\"type\":\"invalid_request_error\"}}"
        // The messages below are what the old code produced: the whole body as the message.
        val leaky = listOf(Exception(body), IOException(body), IllegalStateException(body), RuntimeException(body))
        for (stage in stages) for (e in leaky) {
            val t = thrown(stage, e)
            for (out in listOf(sentence(t), full(t), ErrorMessageMapper.logLine(stage, e))) {
                assertFalse(out, out.contains(key))
                assertFalse(out, out.contains("gsk_"))
                assertFalse(out, out.contains("Invalid API Key"))
                assertFalse(out, out.contains("{"))
                assertFalse(out, out.contains("invalid_request_error"))
            }
        }
        // Same for every HTTP failure the transcriber / polish client can raise: only the code is known.
        for (stage in stages) for (code in listOf(401, 403, 429, 503, 418)) {
            val t = http(stage, code)
            for (out in listOf(sentence(t), full(t))) {
                assertFalse(out, out.contains(key))
                assertFalse(out, out.contains("Invalid API Key"))
                assertFalse(out, out.contains("{"))
            }
        }
        // The status exception itself carries the code only.
        assertEquals("HTTP 401", HttpStatusException(401).message)
    }

    @Test
    fun theLogLineHasTheStageAndTheCodeOrTheClassName() {
        assertEquals("transcribe failed: HTTP 401", ErrorMessageMapper.logLine(ErrorStage.TRANSCRIBE, HttpStatusException(401)))
        assertEquals("polish failed: HTTP 503", ErrorMessageMapper.logLine(ErrorStage.POLISH, HttpStatusException(503)))
        assertEquals(
            "transcribe failed: SocketTimeoutException",
            ErrorMessageMapper.logLine(ErrorStage.TRANSCRIBE, SocketTimeoutException("secret detail"))
        )
        assertEquals(
            "transcribe failed: UnknownHostException",
            ErrorMessageMapper.logLine(ErrorStage.TRANSCRIBE, UnknownHostException("api.groq.com"))
        )
        assertEquals(
            "transcribe failed: setting missing",
            ErrorMessageMapper.logLine(ErrorStage.TRANSCRIBE, SettingsMissingException("Error: Endpoint is not set in settings."))
        )
    }

    @Test
    fun everyErrorStringIsOneLineAndUsesFullWidthPunctuation() {
        for ((_, name) in names) {
            val text = strings.getValue(name)
            assertFalse(name, text.contains('\n'))
            assertFalse(name, text.contains(":"))
            assertFalse(name, text.contains("("))
        }
        assertTrue(strings.getValue("kb_err_stage_transcribe").contains("："))
        assertTrue(strings.getValue("kb_err_full_http_code").contains("（"))
    }
}
