package com.kairosvector.flowchat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ROUND 18: Dual-track fallback key and model mechanism for HTTP 429.
 */
class Round18FallbackTest {

    @Test
    fun testConfigFallbackApiKeyDefault() {
        val config = PolishConfig(
            enabled = true,
            endpoint = DEFAULT_LLM_ENDPOINT,
            model = DEFAULT_LLM_MODEL,
            apiKey = "primary_key",
            prompt = "prompt"
        )
        assertEquals("", config.fallbackApiKey)
        assertEquals(DEFAULT_LLM_FALLBACK_MODEL, config.fallbackModel)
        assertEquals("fallback-api-key", FALLBACK_API_KEY.name)
    }

    @Test
    fun testResultOkFlags() {
        val ok1 = PolishClient.Result.Ok(
            text = "潤飾成功",
            usedFallbackModel = false,
            usedFallbackKey = true
        )
        assertTrue(ok1.usedFallbackKey)
        assertFalse(ok1.usedFallbackModel)

        val ok2 = PolishClient.Result.Ok(
            text = "潤飾成功",
            usedFallbackModel = true,
            usedFallbackKey = false
        )
        assertFalse(ok2.usedFallbackKey)
        assertTrue(ok2.usedFallbackModel)
    }

    @Test
    fun testFallbackKeyRetriedOn429() {
        val callCount = AtomicInteger(0)
        val recordedAuthHeaders = mutableListOf<String>()
        val recordedModels = mutableListOf<String>()

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = callCount.incrementAndGet()
                val req = chain.request()
                recordedAuthHeaders.add(req.header("Authorization") ?: "")

                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyJson = JSONObject(buf.readUtf8())
                recordedModels.add(bodyJson.optString("model"))

                if (count == 1) {
                    // First call fails with 429
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("{\"error\":{\"message\":\"Rate limit reached\"}}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // Second call succeeds
                    val respJson = JSONObject().apply {
                        put("choices", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("content", "潤飾結果使用備用金鑰")
                                })
                            })
                        })
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val config = PolishConfig(
            enabled = true,
            endpoint = "https://mock.api/v1/chat/completions",
            model = "qwen/qwen3.8-27b",
            apiKey = "primary_secret_123",
            prompt = "prompt",
            fallbackModel = "openai/gpt-oss-20b",
            fallbackApiKey = "fallback_secret_456"
        )

        val result = PolishClient.polishDetailed(
            "測試語音輸入文字內容",
            config,
            httpClient = fakeClient,
            callTimeoutMs = 8000L
        )

        assertTrue("Expected Result.Ok, got $result", result is PolishClient.Result.Ok)
        val ok = result as PolishClient.Result.Ok
        assertEquals("潤飾結果使用備用金鑰", ok.text)
        assertTrue("usedFallbackKey should be true", ok.usedFallbackKey)
        assertFalse("usedFallbackModel should be false", ok.usedFallbackModel)

        assertEquals(2, callCount.get())
        assertEquals("Bearer primary_secret_123", recordedAuthHeaders[0])
        assertEquals("Bearer fallback_secret_456", recordedAuthHeaders[1])
        // Model stays primary model
        assertEquals("qwen/qwen3.8-27b", recordedModels[0])
        assertEquals("qwen/qwen3.8-27b", recordedModels[1])
    }

    @Test
    fun testFallbackModelRetriedOn429WhenNoFallbackKey() {
        val callCount = AtomicInteger(0)
        val recordedAuthHeaders = mutableListOf<String>()
        val recordedModels = mutableListOf<String>()

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = callCount.incrementAndGet()
                val req = chain.request()
                recordedAuthHeaders.add(req.header("Authorization") ?: "")

                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyJson = JSONObject(buf.readUtf8())
                recordedModels.add(bodyJson.optString("model"))

                if (count == 1) {
                    // First call fails with 429
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("{\"error\":{\"message\":\"Rate limit reached\"}}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // Second call succeeds
                    val respJson = JSONObject().apply {
                        put("choices", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("content", "潤飾結果使用備用模型")
                                })
                            })
                        })
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val config = PolishConfig(
            enabled = true,
            endpoint = "https://mock.api/v1/chat/completions",
            model = "qwen/qwen3.8-27b",
            apiKey = "primary_secret_123",
            prompt = "prompt",
            fallbackModel = "openai/gpt-oss-20b",
            fallbackApiKey = "" // Blank fallback key
        )

        val result = PolishClient.polishDetailed(
            "測試語音輸入文字內容",
            config,
            httpClient = fakeClient,
            callTimeoutMs = 8000L
        )

        assertTrue("Expected Result.Ok, got $result", result is PolishClient.Result.Ok)
        val ok = result as PolishClient.Result.Ok
        assertEquals("潤飾結果使用備用模型", ok.text)
        assertFalse("usedFallbackKey should be false", ok.usedFallbackKey)
        assertTrue("usedFallbackModel should be true", ok.usedFallbackModel)

        assertEquals(2, callCount.get())
        assertEquals("Bearer primary_secret_123", recordedAuthHeaders[0])
        assertEquals("Bearer primary_secret_123", recordedAuthHeaders[1])
        assertEquals("qwen/qwen3.8-27b", recordedModels[0])
        assertEquals("openai/gpt-oss-20b", recordedModels[1])
    }

    @Test
    fun testNoRetryWhenRemainingTimeoutLow() {
        val callCount = AtomicInteger(0)
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("{\"error\":{\"message\":\"Rate limit reached\"}}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val config = PolishConfig(
            enabled = true,
            endpoint = "https://mock.api/v1/chat/completions",
            model = "qwen/qwen3.8-27b",
            apiKey = "key",
            prompt = "prompt",
            fallbackModel = "openai/gpt-oss-20b",
            fallbackApiKey = "fallback_key"
        )

        // remaining timeout < 500ms -> should not retry
        val result = PolishClient.polishDetailed(
            "測試語音輸入文字內容",
            config,
            httpClient = fakeClient,
            callTimeoutMs = 400L
        )

        assertTrue("Expected Result.Failed, got $result", result is PolishClient.Result.Failed)
        assertEquals(1, callCount.get())
    }

    @Test
    fun testSettingsLayoutXmlContainsFallbackKey() {
        val xml = File("src/main/res/layout/activity_main.xml").readText(Charsets.UTF_8)
        assertTrue("til_fallback_api_key in activity_main.xml", xml.contains("android:id=\"@+id/til_fallback_api_key\""))
        assertTrue("edit_fallback_api_key in activity_main.xml", xml.contains("android:id=\"@+id/edit_fallback_api_key\""))
    }

    @Test
    fun testStringsXmlContainsFallbackStrings() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertTrue(xml.contains("name=\"st_ai_fallback_key\""))
        assertTrue(xml.contains("name=\"st_ai_fallback_key_helper\""))
        assertTrue(xml.contains("name=\"st_ai_fallback_key_placeholder\""))
        assertTrue(xml.contains("name=\"kb_status_fallback_key\""))
        assertTrue(xml.contains("name=\"kb_status_fallback_model\""))
    }
}
