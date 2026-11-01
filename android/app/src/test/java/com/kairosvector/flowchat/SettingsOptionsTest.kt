package com.kairosvector.flowchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 6: the settings page shows translated labels, but the value that is
 * STORED (and compared by WhisperTranscriber / WhisperInputService) must stay
 * the original English string, so old settings keep matching.
 */
class SettingsOptionsTest {
    // ---- option <-> stored value ------------------------------------------------

    @Test
    fun backendOptionsStoreTheOriginalEnglishStrings() {
        assertEquals(
            listOf("OpenAI API", "Whisper ASR Webservice", "NVIDIA NIM"),
            SettingsOptions.backends.map { it.stored }
        )
    }

    @Test
    fun postprocessingOptionsStoreTheOriginalEnglishStrings() {
        assertEquals(
            listOf("Convert to Traditional Chinese", "Convert to Simplified Chinese", "No Conversion"),
            SettingsOptions.postprocessing.map { it.stored }
        )
    }

    @Test
    fun everyOptionHasItsOwnLabel() {
        for (options in listOf(SettingsOptions.backends, SettingsOptions.postprocessing)) {
            val labels = options.map { it.label }
            assertEquals("labels must be distinct", labels.size, labels.toSet().size)
            assertTrue(labels.all { it != 0 })
        }
        assertTrue(SettingsOptions.backends.all { it.sub != null })
    }

    @Test
    fun selectingAnOptionStoresItsOriginalString() {
        // display position -> stored value, for every option
        SettingsOptions.backends.forEachIndexed { i, option ->
            assertEquals(option.stored, SettingsOptions.storedAt(SettingsOptions.backends, i))
        }
        SettingsOptions.postprocessing.forEachIndexed { i, option ->
            assertEquals(option.stored, SettingsOptions.storedAt(SettingsOptions.postprocessing, i))
        }
    }

    @Test
    fun anOldStoredValueSelectsTheMatchingOption() {
        // values written by every earlier version of the app
        assertEquals(0, SettingsOptions.indexOfStored(SettingsOptions.backends, "OpenAI API"))
        assertEquals(1, SettingsOptions.indexOfStored(SettingsOptions.backends, "Whisper ASR Webservice"))
        assertEquals(2, SettingsOptions.indexOfStored(SettingsOptions.backends, "NVIDIA NIM"))
        assertEquals(0, SettingsOptions.indexOfStored(SettingsOptions.postprocessing, "Convert to Traditional Chinese"))
        assertEquals(1, SettingsOptions.indexOfStored(SettingsOptions.postprocessing, "Convert to Simplified Chinese"))
        assertEquals(2, SettingsOptions.indexOfStored(SettingsOptions.postprocessing, "No Conversion"))
    }

    @Test
    fun anUnknownStoredValueSelectsNothing() {
        assertEquals(-1, SettingsOptions.indexOfStored(SettingsOptions.backends, "Some Future Service"))
        assertEquals(-1, SettingsOptions.indexOfStored(SettingsOptions.backends, null))
        assertEquals(-1, SettingsOptions.indexOfStored(SettingsOptions.postprocessing, "轉繁體"))
    }

    // ---- the constants really are the strings the rest of the app compares with ----

    @Test
    fun storedStringsMatchDonottranslateXml() {
        val xml = File("src/main/res/values/donottranslate.xml").readText(Charsets.UTF_8)
        fun value(name: String): String {
            val m = Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml)
            assertTrue("$name missing in donottranslate.xml", m != null)
            return m!!.groupValues[1]
        }
        assertEquals(SettingsOptions.BACKEND_OPENAI, value("settings_option_openai_api"))
        assertEquals(SettingsOptions.BACKEND_ASR, value("settings_option_whisper_asr_webservice"))
        assertEquals(SettingsOptions.BACKEND_NIM, value("settings_option_nvidia_nim"))
        assertEquals(SettingsOptions.POSTPROCESS_TRADITIONAL, value("settings_option_to_traditional"))
        assertEquals(SettingsOptions.POSTPROCESS_SIMPLIFIED, value("settings_option_to_simplified"))
        assertEquals(SettingsOptions.POSTPROCESS_NONE, value("settings_option_no_conversion"))
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_OPENAI, value("settings_option_openai_api_default_endpoint"))
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_ASR, value("settings_option_whisper_asr_webservice_default_endpoint"))
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_NIM, value("settings_option_nvidia_nim_default_endpoint"))
        assertEquals(SettingsOptions.DEFAULT_MODEL_OPENAI, value("settings_option_openai_api_default_model"))
    }

    @Test
    fun donottranslateStringsAreNotTranslatable() {
        val xml = File("src/main/res/values/donottranslate.xml").readText(Charsets.UTF_8)
        val strings = Regex("<string ").findAll(xml).count()
        val flagged = Regex("translatable=\"false\"").findAll(xml).count()
        assertTrue(strings > 0)
        assertEquals(strings, flagged)
    }

    // ---- switching the service keeps what the user typed ---------------------------

    @Test
    fun switchingServiceReplacesOnlyDefaultsAndBlanks() {
        val groq = SettingsOptions.DEFAULT_ENDPOINT_OPENAI
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_ASR, SettingsOptions.endpointAfterSwitch(SettingsOptions.BACKEND_ASR, groq))
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_NIM, SettingsOptions.endpointAfterSwitch(SettingsOptions.BACKEND_NIM, ""))
        assertEquals(SettingsOptions.DEFAULT_ENDPOINT_OPENAI, SettingsOptions.endpointAfterSwitch(SettingsOptions.BACKEND_OPENAI, SettingsOptions.DEFAULT_ENDPOINT_NIM))
        // a custom URL is kept
        assertEquals("http://my-box:8000/asr", SettingsOptions.endpointAfterSwitch(SettingsOptions.BACKEND_ASR, "http://my-box:8000/asr"))
        assertEquals("https://example.com/v1/audio/transcriptions",
            SettingsOptions.endpointAfterSwitch(SettingsOptions.BACKEND_OPENAI, "https://example.com/v1/audio/transcriptions"))
    }

    @Test
    fun switchingServiceKeepsACustomModel() {
        assertEquals("", SettingsOptions.modelAfterSwitch(SettingsOptions.BACKEND_ASR, SettingsOptions.DEFAULT_MODEL_OPENAI))
        assertEquals(SettingsOptions.DEFAULT_MODEL_OPENAI, SettingsOptions.modelAfterSwitch(SettingsOptions.BACKEND_OPENAI, ""))
        assertEquals("whisper-large-v3", SettingsOptions.modelAfterSwitch(SettingsOptions.BACKEND_OPENAI, "whisper-large-v3"))
        assertEquals("my-model", SettingsOptions.modelAfterSwitch(SettingsOptions.BACKEND_ASR, "my-model"))
    }

    @Test
    fun multiLanguageBelongsToNimOnly() {
        assertEquals("multi", SettingsOptions.languageAfterSwitch(SettingsOptions.BACKEND_NIM, "auto"))
        assertEquals("auto", SettingsOptions.languageAfterSwitch(SettingsOptions.BACKEND_OPENAI, "multi"))
        assertEquals("zh", SettingsOptions.languageAfterSwitch(SettingsOptions.BACKEND_NIM, "zh"))
        assertEquals("ja", SettingsOptions.languageAfterSwitch(SettingsOptions.BACKEND_ASR, "ja"))
        assertNotEquals("multi", SettingsOptions.languageAfterSwitch(SettingsOptions.BACKEND_ASR, "multi"))
    }
}
