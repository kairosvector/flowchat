package com.kairosvector.flowchat

import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 11b / Round 19: the tone prompts, the migration from the single prompt, the tone list and its rules. */
class ToneTest {
    private fun tone(id: String, name: String = id, builtin: Boolean = false) =
        Tone(id, name, ToneRules.defaultShort(name), "d", "prompt of $id <原文> 特別注意", builtin)

    // ---- the built-in instructions ----

    @Test
    fun theBuiltInProInstructionUsesBasePromptAndProAddon() {
        assertEquals(BasePrompt.build(TonePrompts.PRO_TEXT), TonePrompts.PRO)
    }

    @Test
    fun theBuiltInChatInstructionUsesBasePromptAndEqChatAddon() {
        assertEquals(BasePrompt.build(TonePrompts.EQ_CHAT_TEXT), TonePrompts.CHAT)
    }

    @Test
    fun tonePreviewExtractsFromToneKeywordWithEllipsis() {
        val prompt = "前面的系統提示文字。\n\n語氣：專業、清楚、有禮，適合工作上的訊息。"
        assertEquals("…語氣：專業、清楚、有禮，適合工作上的訊息。", TonePreview.of(prompt))
    }

    @Test
    fun tonePreviewFallsBackToStartWhenKeywordIsMissing() {
        val prompt = "自訂提示詞\n  第二行   第三行"
        assertEquals("自訂提示詞 第二行 第三行", TonePreview.of(prompt))
    }

    @Test
    fun builtInTonesHaveDistinctPreviewsStartingWithTone() {
        val proPreview = TonePreview.of(TonePrompts.PRO)
        val chatPreview = TonePreview.of(TonePrompts.CHAT)
        org.junit.Assert.assertNotEquals(proPreview, chatPreview)
    }

    @Test
    fun theDefaultTonesAreFourModularAddons() {
        val tones = TonePrompts.defaultTones()
        assertEquals(listOf(TONE_BASIC, TONE_PRO, TONE_EQ_CHAT, TONE_HUMOR_CHAT), tones.map { it.id })
        assertEquals(listOf("基礎整理", "專業潤飾", "高情商聊天", "幽默聊天"), tones.map { it.name })
        assertEquals(listOf("去贅字修標點，原汁原味", "工作訊息，條理成熟", "溫暖體貼，化解生硬", "輕鬆風趣，帶梗不尬"), tones.map { it.desc })
        assertTrue(tones.all { it.builtin && it.promptVersion == 6 })
        assertEquals(6, POLISH_PROMPT_VERSION)
    }

    // ---- migration from llm-prompt / polish-enabled ----

    @Test
    fun anUntouchedOldPromptBecomesTheNewProDefault() {
        for (old in listOf(null, "", "   \n", DEFAULT_POLISH_PROMPT, LEGACY_POLISH_PROMPT_V4, LEGACY_POLISH_PROMPT_V2, LEGACY_POLISH_PROMPT_V1)) {
            val out = ToneMigration.migrate(old, true)
            assertEquals(TonePrompts.PRO, out.tones[1].prompt)
        }
    }

    @Test
    fun upgradeTonesUpdatesUntouchedBuiltinsToV6WhilePreservingCustom() {
        val v4Pro = Tone(TONE_PRO, "專業", "專業", "工作訊息", TonePrompts.PRO_V4, builtin = true, promptVersion = 4)
        val v4Chat = Tone(TONE_CHAT, "聊天", "聊天", "口語＋emoji", TonePrompts.CHAT_V4, builtin = true, promptVersion = 4)
        val customTone = Tone("u_custom", "日誌", "日誌", "自訂", "自訂 prompt <原文> 特別注意", builtin = false, promptVersion = 4)
        val customizedPro = Tone(TONE_PRO, "專業", "專業", "工作訊息", "修改過的 prompt <原文> 特別注意", builtin = true, promptVersion = 4)

        val upgraded = ToneMigration.upgradeTones(listOf(v4Pro, v4Chat, customTone))
        assertEquals(TonePrompts.PRO, upgraded.first { it.id == TONE_PRO }.prompt)
        assertEquals(6, upgraded.first { it.id == TONE_PRO }.promptVersion)
        assertEquals(TonePrompts.EQ_CHAT, upgraded.first { it.id == TONE_EQ_CHAT }.prompt)
        assertEquals(6, upgraded.first { it.id == TONE_EQ_CHAT }.promptVersion)
        assertEquals("自訂 prompt <原文> 特別注意", upgraded.first { it.id == "u_custom" }.prompt)
        assertEquals(4, upgraded.first { it.id == "u_custom" }.promptVersion)

        val preservedCustomPro = ToneMigration.upgradeTones(listOf(customizedPro))
        assertEquals("修改過的 prompt <原文> 特別注意", preservedCustomPro.first { it.id == TONE_PRO }.prompt)
    }

    @Test
    fun anEditedOldPromptIsKeptAsTheProInstruction() {
        val mine = "我自己改過的指令 <原文> 特別注意"
        val out = ToneMigration.migrate(mine, true)
        assertEquals(mine, out.tones[1].prompt)
        assertEquals(TONE_PRO, out.tones[1].id)
        assertEquals("專業潤飾", out.tones[1].name)
    }

    @Test
    fun chatIsAlwaysTheNewDefault() {
        assertEquals(TonePrompts.CHAT, ToneMigration.migrate("edited", true).tones[2].prompt)
        assertEquals(TonePrompts.CHAT, ToneMigration.migrate(null, null).tones[2].prompt)
    }

    @Test
    fun polishingOffBecomesToneOffAndEverythingElseBecomesBasic() {
        assertEquals(TONE_OFF, ToneMigration.migrate(null, false).polishTone)
        assertEquals(TONE_OFF, ToneMigration.migrate("edited", false).polishTone)
        assertEquals(TONE_BASIC, ToneMigration.migrate(null, true).polishTone)
        assertEquals(TONE_BASIC, ToneMigration.migrate(null, null).polishTone)
    }

    // ---- tones-json ----

    @Test
    fun theToneListRoundTripsInOrder() {
        val list = listOf(tone("pro", "專業", true), tone("chat", "聊天", true), tone("u_1730368000000", "Email"))
        assertEquals(list, ToneCodec.parse(ToneCodec.encode(list)))
    }

    @Test
    fun eightTonesRoundTripAndTheLimitIsEight() {
        val eight = (1..ToneRules.MAX_TONES).map { tone("u_$it", "T$it") }
        assertEquals(eight, ToneCodec.parse(ToneCodec.encode(eight)))
        assertEquals(8, ToneRules.MAX_TONES)
        assertTrue(ToneRules.canAdd(7))
        assertFalse(ToneRules.canAdd(8))
    }

    @Test
    fun brokenOrEmptyJsonReadsAsMissing() {
        assertNull(ToneCodec.parse(null))
        assertNull(ToneCodec.parse(""))
        assertNull(ToneCodec.parse("not json"))
        assertNull(ToneCodec.parse("[]"))
        assertNull(ToneCodec.parse("""[{"id":"x","name":"x","prompt":""}]"""))
    }

    @Test
    fun theOffIdAndDuplicateIdsAreNeverATone() {
        val json = """[{"id":"off","name":"關","prompt":"p"},{"id":"a","name":"A","prompt":"p1"},{"id":"a","name":"B","prompt":"p2"}]"""
        val tones = ToneCodec.parse(json)!!
        assertEquals(listOf("a"), tones.map { it.id })
        assertEquals("p1", tones[0].addonPrompt)
    }

    @Test
    fun aMissingShortLabelFallsBackToTheFirstTwoCharacters() {
        val tones = ToneCodec.parse("""[{"id":"a","name":"郵件助理","prompt":"p"}]""")!!
        assertEquals("郵件", tones[0].short)
    }

    // ---- which tone is current ----

    @Test
    fun withNothingSavedTheStateIsBasicOn() {
        val state = ToneStore.resolve(preferencesOf())
        assertEquals(listOf(TONE_BASIC, TONE_PRO, TONE_EQ_CHAT, TONE_HUMOR_CHAT), state.tones.map { it.id })
        assertEquals(TONE_BASIC, state.currentId)
        assertTrue(state.polishOn)
    }

    @Test
    fun anOldPolishEnabledFalseReadsAsOffBeforeTheMigrationRuns() {
        val state = ToneStore.resolve(preferencesOf(POLISH_ENABLED to false))
        assertEquals(TONE_OFF, state.currentId)
        assertFalse(state.polishOn)
    }

    @Test
    fun theSavedToneIsUsedAndAnUnknownOneFallsBackToTheFirst() {
        val json = ToneCodec.encode(listOf(tone("a"), tone("b")))
        assertEquals("b", ToneStore.resolve(preferencesOf(TONES_JSON to json, POLISH_TONE to "b")).currentId)
        assertEquals("a", ToneStore.resolve(preferencesOf(TONES_JSON to json, POLISH_TONE to "gone")).currentId)
        assertEquals(TONE_OFF, ToneStore.resolve(preferencesOf(TONES_JSON to json, POLISH_TONE to "off")).currentId)
    }

    // ---- editor rules ----

    @Test
    fun aNameIsOneToEightCharactersAndUnique() {
        assertNull(ToneRules.nameError("Email", listOf("專業", "聊天")))
        assertEquals(ToneRules.NameError.LENGTH, ToneRules.nameError("", emptyList()))
        assertEquals(ToneRules.NameError.LENGTH, ToneRules.nameError("   ", emptyList()))
        assertNull(ToneRules.nameError("一二三四五六七八", emptyList()))
        assertEquals(ToneRules.NameError.LENGTH, ToneRules.nameError("一二三四五六七八九", emptyList()))
        assertEquals(ToneRules.NameError.DUPLICATE, ToneRules.nameError("聊天", listOf("專業", "聊天")))
        assertEquals(ToneRules.NameError.DUPLICATE, ToneRules.nameError("email", listOf("Email")))
    }

    @Test
    fun theKeyLabelIsOneOrTwoFullWidthOrUpToFourHalfWidthCharacters() {
        assertTrue(ToneRules.shortValid("專"))
        assertTrue(ToneRules.shortValid("專業"))
        assertFalse(ToneRules.shortValid("專業人"))
        assertTrue(ToneRules.shortValid("Mail"))
        assertFalse(ToneRules.shortValid("Email"))
        assertTrue(ToneRules.shortValid("專a"))
        assertFalse(ToneRules.shortValid(""))
        assertFalse(ToneRules.shortValid("  "))
    }

    @Test
    fun theDefaultKeyLabelIsTheFirstTwoCharactersOfTheName() {
        assertEquals("新語", ToneRules.defaultShort("新語氣"))
        assertEquals("A", ToneRules.defaultShort("A"))
        assertEquals("Em", ToneRules.defaultShort(" Email "))
    }

    @Test
    fun aPromptNeedsTextAndAtMost4000Characters() {
        assertTrue(ToneRules.promptValid("x"))
        assertFalse(ToneRules.promptValid("   "))
        assertTrue(ToneRules.promptValid("x".repeat(4000)))
        assertFalse(ToneRules.promptValid("x".repeat(4001)))
    }

    @Test
    fun theInjectionGuardIsAlwaysTrueInModularArchitecture() {
        assertTrue(ToneRules.hasInjectionGuard(TonePrompts.PRO))
        assertTrue(ToneRules.hasInjectionGuard(TonePrompts.CHAT))
        assertTrue(ToneRules.hasInjectionGuard("整理成通順的句子"))
    }
}
