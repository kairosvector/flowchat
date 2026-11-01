package com.example.whispertoinput

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroqRateLimitAndFallbackTest {

    @Test
    fun testQwenDisablesReasoningEffort() {
        val qwenModels = listOf("qwen/qwen3.8-27b", "Qwen/Qwen2.5-72B-Instruct", "qwen-turbo")
        for (model in qwenModels) {
            val body = JSONObject()
            body.put("model", model)
            if (model.contains("qwen", ignoreCase = true)) {
                body.put("reasoning_effort", "none")
            }
            assertTrue("Expected reasoning_effort=none for $model", body.has("reasoning_effort"))
            assertEquals("none", body.getString("reasoning_effort"))
        }

        val nonQwenModels = listOf("openai/gpt-oss-20b", "llama-3.3-70b-versatile", "gemma2-9b-it")
        for (model in nonQwenModels) {
            val body = JSONObject()
            body.put("model", model)
            if (model.contains("qwen", ignoreCase = true)) {
                body.put("reasoning_effort", "none")
            }
            assertFalse("Expected no reasoning_effort for $model", body.has("reasoning_effort"))
        }
    }

    @Test
    fun testDefaultFallbackModel() {
        assertEquals("openai/gpt-oss-20b", DEFAULT_LLM_FALLBACK_MODEL)
        val config = PolishConfig(
            enabled = true,
            endpoint = DEFAULT_LLM_ENDPOINT,
            model = DEFAULT_LLM_MODEL,
            apiKey = "test_key",
            prompt = "test_prompt"
        )
        assertEquals("openai/gpt-oss-20b", config.fallbackModel)
    }

    @Test
    fun testTokenUsageRecording() {
        val usage = TokenUsage(promptTokens = 120, completionTokens = 45, totalTokens = 165)
        assertEquals(120, usage.promptTokens)
        assertEquals(45, usage.completionTokens)
        assertEquals(165, usage.totalTokens)

        val okResult = PolishClient.Result.Ok(
            text = "整理後的文字",
            usage = usage,
            usedFallbackModel = true
        )
        assertTrue(okResult.usedFallbackModel)
        assertNotNull(okResult.usage)
        assertEquals(165, okResult.usage?.totalTokens)
    }

    @Test
    fun testRateLimitErrorTextWithRetryAfter() {
        val errWithRetry = ErrorMessageMapper.fromHttp(ErrorStage.POLISH, 429, retryAfterSeconds = 7)
        assertEquals(R.string.kb_err_rate_limited_retry_after, errWithRetry.reasonRes)
        assertEquals(R.string.kb_err_full_rate_limited_retry_after, errWithRetry.fullReasonRes)
        assertEquals(7, errWithRetry.retryAfterSeconds)

        val errWithoutRetry = ErrorMessageMapper.fromHttp(ErrorStage.POLISH, 429, retryAfterSeconds = null)
        assertEquals(R.string.kb_err_rate_limited, errWithoutRetry.reasonRes)
        assertEquals(R.string.kb_err_full_rate_limited, errWithoutRetry.fullReasonRes)
        assertNull(errWithoutRetry.retryAfterSeconds)
    }
}
