package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Round 6: what the "測試連線" button says for each kind of answer (fake keys only). */
class ConnectionTestMapperTest {
    private val key = "gsk_test_xxxx3f9a"

    private fun http(code: Int, body: String = "", model: String = "whisper-large-v3-turbo") =
        ConnectionTestMapper.fromHttp(code, body, model, listOf(key))

    @Test
    fun http401MeansInvalidKey() {
        val r = http(401)
        assertEquals(FailureKind.INVALID_KEY, r.kind)
        assertEquals(401, r.code)
    }

    @Test
    fun http403MeansInvalidKey() {
        val r = http(403)
        assertEquals(FailureKind.INVALID_KEY, r.kind)
        assertEquals(403, r.code)
    }

    @Test
    fun http404WithAModelMeansModelNotFound() {
        assertEquals(FailureKind.MODEL_NOT_FOUND, http(404).kind)
    }

    @Test
    fun http404WithoutAModelIsJustAnError() {
        // self-hosted services have no model field: a 404 is a wrong URL
        val r = http(404, "not found", model = "")
        assertEquals(FailureKind.OTHER, r.kind)
        assertEquals(404, r.code)
    }

    @Test
    fun aModelMissingFromTheListMeansModelNotFound() {
        val r = ConnectionTestMapper.modelMissing("gone-model")
        assertEquals(FailureKind.MODEL_NOT_FOUND, r.kind)
        assertNull(r.code)
        assertEquals("gone-model", r.detail)
    }

    @Test
    fun http429MeansRateLimited() {
        assertEquals(FailureKind.RATE_LIMITED, http(429).kind)
    }

    @Test
    fun http500IsAnOtherErrorWithTheCode() {
        val r = http(500, "internal error")
        assertEquals(FailureKind.OTHER, r.kind)
        assertEquals(500, r.code)
        assertEquals("internal error", r.detail)
    }

    @Test
    fun theDetailIsCutAt120Characters() {
        val r = http(500, "x".repeat(500))
        assertEquals(120, r.detail.length)
    }

    @Test
    fun aTimeoutMeansUnreachable() {
        val r = ConnectionTestMapper.fromException(SocketTimeoutException("timeout"), "api.groq.com")
        assertEquals(FailureKind.UNREACHABLE, r.kind)
        assertEquals("api.groq.com", r.detail)
        assertNull(r.code)
    }

    @Test
    fun aDnsFailureMeansUnreachable() {
        val r = ConnectionTestMapper.fromException(UnknownHostException("no such host"), "example.invalid")
        assertEquals(FailureKind.UNREACHABLE, r.kind)
        assertEquals("example.invalid", r.detail)
    }

    // ---- the key never shows up ----------------------------------------------------

    @Test
    fun theKeyIsRemovedFromTheResponseText() {
        val r = http(401, "Invalid API key: $key provided")
        assertFalse(r.detail.contains(key))
        assertTrue(r.detail.contains("••••"))
    }

    @Test
    fun bearerTokensAndKeyShapedStringsAreRemoved() {
        val out = ConnectionTestMapper.redact("Authorization: Bearer abc.def-123 and gsk_AbCdEfGh12345678", emptyList())
        assertFalse(out.contains("abc.def"))
        assertFalse(out.contains("AbCdEfGh"))
    }

    @Test
    fun aBlankSecretIsIgnored() {
        assertEquals("hello", ConnectionTestMapper.redact("hello", listOf("", "  ")).trim())
    }

    // ---- model list and URLs --------------------------------------------------------

    @Test
    fun modelListedFindsTheId() {
        val body = """{"object":"list","data":[{"id":"whisper-large-v3-turbo","object":"model"},{"id":"llama-3.3-70b"}]}"""
        assertTrue(ConnectionTestMapper.modelListed(body, "whisper-large-v3-turbo"))
        assertTrue(ConnectionTestMapper.modelListed(body, "llama-3.3-70b"))
        assertFalse(ConnectionTestMapper.modelListed(body, "whisper-large"))
        assertFalse(ConnectionTestMapper.modelListed(body, ""))
        assertFalse(ConnectionTestMapper.modelListed("not json", "x"))
    }

    @Test
    fun baseUrlDropsTheEndpointSuffixAndQuery() {
        assertEquals(
            "https://api.groq.com/openai/v1",
            ConnectionTestMapper.baseUrl("https://api.groq.com/openai/v1/audio/transcriptions", "/audio/transcriptions")
        )
        assertEquals(
            "https://api.groq.com/openai/v1",
            ConnectionTestMapper.baseUrl("https://api.groq.com/openai/v1/chat/completions/?x=1", "/chat/completions")
        )
    }

    @Test
    fun hostOfAUrl() {
        assertEquals("api.groq.com", ConnectionTestMapper.hostOf("https://api.groq.com/openai/v1"))
        assertEquals("", ConnectionTestMapper.hostOf("not a url"))
    }
}
