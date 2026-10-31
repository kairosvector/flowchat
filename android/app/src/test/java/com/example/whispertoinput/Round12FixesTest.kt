package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 12: the polish-failure flow and the string-resource descriptions of the built-in tones. */
class Round12FixesTest {
    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        Regex("<string name=\"([^\"]+)\"[^>]*>([^<]*)</string>")
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // ---- what happens to a finished transcription ----

    @Test
    fun aPolishedResultWaitsOnTheCard() {
        assertEquals(ResultMode.PENDING_CARD, ResultFlow.forNote(PolishNote.OK))
    }

    @Test
    fun aFailedPolishInsertsTheOriginalRightAway() {
        // v19 behaviour, restored: the original goes in and the card offers the retry
        assertEquals(ResultMode.INSERTED_WITH_RETRY, ResultFlow.forNote(PolishNote.FAILED))
    }

    @Test
    fun polishingOffOrAShortSentenceInsertsDirectlyWithoutACard() {
        assertEquals(ResultMode.DIRECT_INSERT, ResultFlow.forNote(PolishNote.OFF))
        assertEquals(ResultMode.DIRECT_INSERT, ResultFlow.forNote(PolishNote.TOO_SHORT))
    }

    @Test
    fun everyNoteHasExactlyOneMode() {
        for (note in PolishNote.values()) assertEquals(note.name, ResultFlow.forNote(note), ResultFlow.forNote(note))
    }

    // ---- built-in tone descriptions come from string resources ----

    private fun tone(id: String, builtin: Boolean, desc: String) =
        Tone(id, id, id, desc, "p <原文> 特別注意", builtin)

    @Test
    fun aSavedOldChatDescriptionIsShownWithTheNewWording() {
        val saved = ToneCodec.parse(
            """[{"id":"pro","name":"專業","short":"專業","desc":"工作訊息","prompt":"p","builtin":true,"promptVersion":4},
                {"id":"chat","name":"聊天","short":"聊天","desc":"口語＋少量 emoji","prompt":"p","builtin":true,"promptVersion":4}]"""
        )!!
        val chat = saved.first { it.id == TONE_CHAT }
        assertEquals("口語＋少量 emoji", chat.desc)                      // what is stored does not change
        assertEquals(R.string.kb_tone_chat_desc, ToneDisplay.descRes(chat))
        assertEquals("口語＋emoji", strings.getValue("kb_tone_chat_desc"))   // what the menu shows
        assertEquals(R.string.kb_tone_pro_desc, ToneDisplay.descRes(saved.first { it.id == TONE_PRO }))
        assertEquals("工作訊息", strings.getValue("kb_tone_pro_desc"))
    }

    @Test
    fun aCustomToneKeepsItsOwnDescriptionEvenWithABuiltInLookingId() {
        assertNull(ToneDisplay.descRes(tone("u_1", builtin = false, desc = "我的")))
        assertNull(ToneDisplay.descRes(tone("chat", builtin = false, desc = "我的")))
        assertNull(ToneDisplay.descRes(tone("u_2", builtin = true, desc = "怪")))
    }

    @Test
    fun theNewDefaultToneListUsesTheNewChatWording() {
        val chat = TonePrompts.defaultTones().first { it.id == TONE_CHAT }
        assertEquals("口語＋emoji", chat.desc)
        assertFalse(strings.getValue("kb_tone_chat_desc").contains("少量"))
        assertTrue(strings.containsKey("kb_tone_pro_desc"))
    }
}
