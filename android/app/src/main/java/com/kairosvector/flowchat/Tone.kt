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

package com.kairosvector.flowchat

import org.json.JSONArray
import org.json.JSONObject

/**
 * One AI polish tone (SPEC_r8 §14 / §15 / Round 19). [id] is stable ("basic", "pro",
 * "eq_chat", "humor_chat", "u_<millis>" for custom ones); the order of the list is the order shown
 * on the keyboard. [short] is the label on the tone key.
 * [addonPrompt] holds only the tone-specific style instructions (Base Prompt is dynamically prepended).
 * [enabled] controls visibility in the keyboard menu.
 */
data class Tone(
    val id: String,
    val name: String,
    val short: String,
    val desc: String,
    val addonPrompt: String,
    val builtin: Boolean,
    val enabled: Boolean = true,
    val promptVersion: Int = TonePrompts.VERSION
) {
    /**
     * Builds the complete prompt with BasePrompt and optional [lengthMode].
     * Isolates [TONE_BASIC]: length modifier is never applied to basic tone.
     */
    fun buildPrompt(lengthMode: LengthMode? = null): String {
        val modifier = if (id == TONE_BASIC) null else lengthMode?.modifier
        return if (addonPrompt.contains("<原文>") || addonPrompt.contains("你是文字編輯")) {
            addonPrompt
        } else {
            BasePrompt.build(addonPrompt, modifier)
        }
    }

    /** Backward compatibility: computes the full prompt without length modifier. */
    val prompt: String
        get() = buildPrompt(null)
}

/** The id the tone key stores when polishing is off. */
const val TONE_OFF = "off"
const val TONE_BASIC = "basic"
const val TONE_PRO = "pro"
const val TONE_EQ_CHAT = "eq_chat"
const val TONE_HUMOR_CHAT = "humor_chat"
const val TONE_CHAT = "chat" // Legacy alias for eq_chat

/** Pure read / write of the `tones-json` setting (no Android needed, unit tested). */
object ToneCodec {
    /** Tones in order; null when [json] is missing or unreadable (so the caller can fall back). */
    fun parse(json: String?): List<Tone>? {
        if (json.isNullOrBlank()) return null
        return try {
            val array = JSONArray(json)
            val tones = (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val id = o.optString("id", "")
                val addon = o.optString("addonPrompt", "")
                val legacyPrompt = o.optString("prompt", "")
                val promptText = if (addon.isNotBlank()) addon else legacyPrompt
                if (id.isEmpty() || id == TONE_OFF || (promptText.isBlank() && TonePrompts.builtinAddon(id) == null)) {
                    return@mapNotNull null
                }
                val name = o.optString("name", "").ifBlank { id }
                val enabled = if (id == TONE_BASIC) true else o.optBoolean("enabled", true)
                val finalAddon = if (promptText.isNotBlank()) promptText else TonePrompts.builtinAddon(id).orEmpty()
                Tone(
                    id = id,
                    name = name,
                    short = o.optString("short", "").ifBlank { ToneRules.defaultShort(name) },
                    desc = o.optString("desc", ""),
                    addonPrompt = finalAddon,
                    builtin = o.optBoolean("builtin", false),
                    enabled = enabled,
                    promptVersion = o.optInt("promptVersion", TonePrompts.VERSION)
                )
            }
            // Two tones with one id would make "the current tone" ambiguous.
            tones.distinctBy { it.id }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun encode(tones: List<Tone>): String {
        val array = JSONArray()
        for (tone in tones) {
            array.put(
                JSONObject()
                    .put("id", tone.id)
                    .put("name", tone.name)
                    .put("short", tone.short)
                    .put("desc", tone.desc)
                    .put("addonPrompt", tone.addonPrompt)
                    .put("prompt", tone.prompt)
                    .put("builtin", tone.builtin)
                    .put("enabled", if (tone.id == TONE_BASIC) true else tone.enabled)
                    .put("promptVersion", tone.promptVersion)
            )
        }
        return array.toString()
    }
}

/** The limits and checks of the tone editor (SPEC_r8 §14). */
object ToneRules {
    const val MAX_TONES = 8
    const val MAX_NAME_CHARS = 8
    const val MAX_PROMPT_CHARS = 4000

    /** True while another tone may be added (at most [MAX_TONES]). */
    fun canAdd(count: Int): Boolean = count < MAX_TONES

    /** Width in full-width characters: ASCII counts half, everything else one. */
    fun width(text: String): Double = text.sumOf { if (it.code < 0x80) 0.5 else 1.0 }

    /** The first two characters of the name (the default key label). */
    fun defaultShort(name: String): String {
        val trimmed = name.trim()
        val cut = trimmed.offsetByCodePoints(0, minOf(2, trimmed.codePointCount(0, trimmed.length)))
        return trimmed.substring(0, cut)
    }

    /** 1–8 characters, and not the name of another tone (or the word for "off"). */
    fun nameError(name: String, otherNames: List<String>): NameError? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_NAME_CHARS) return NameError.LENGTH
        if (otherNames.any { it.trim().equals(trimmed, ignoreCase = true) }) return NameError.DUPLICATE
        return null
    }

    enum class NameError { LENGTH, DUPLICATE }

    /** 1–2 full-width characters or 1–4 half-width ones. */
    fun shortValid(short: String): Boolean {
        val trimmed = short.trim()
        return trimmed.isNotEmpty() && width(trimmed) <= 2.0
    }

    fun promptValid(prompt: String): Boolean = prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS

    /** In Base+Addon architecture, safety is guaranteed by BasePrompt; legacy check preserved. */
    fun hasInjectionGuard(prompt: String): Boolean = true
}

/**
 * Built-in tone Addon prompts (Round 19).
 */
object TonePrompts {
    const val VERSION = 6

    const val BASIC_TEXT =
        "- 刪除「嗯、呃、那個」等無意義贅字及結巴重複。\n" +
        "- 修正錯字、文法與標點。\n" +
        "- 遇到自我修正時，保留最後說法。\n" +
        "- 保留口語與自然表達，不刻意改成書面語。"

    const val PRO_TEXT =
        "- 將原文整理成清晰、成熟、條理分明的商務表達，避免官腔與過度口語。\n" +
        "- 刪除贅字、重複與不必要的口語助詞（啦、喔、欸），不加 emoji。\n" +
        "- 改善語序與邏輯流暢度，遇到改口自我修正時保留最後說法。\n" +
        "- 維持適合手機訊息的精簡排版，避免多餘的空行與段落拆分。"

    const val EQ_CHAT_TEXT =
        "- 將原文轉為更有溫度、體貼有同理心的日常表達，化解生硬或命令式語氣。\n" +
        "- 遇爭議時適度緩和，但保留原本立場與真實意思，不刻意討好或過度妥協。\n" +
        "- 避免油膩、肉麻或過度煽情；維持台灣日常簡潔表達，篇幅與原文相近不大幅擴寫。\n" +
        "- 自我修正保留最後說法；可在適當處句尾加上最多 1 個貼切 emoji。"

    const val HUMOR_CHAT_TEXT =
        "- 將原文轉為輕鬆風趣、生動有魅力的日常表達；適度運用幽默、自嘲、反差或俏皮接話。\n" +
        "- 遇到嚴肅、悲傷、道歉或敏感情境時，尊重原意，嚴禁強行搞笑。\n" +
        "- 避免低俗冒犯、刻意尷尬或過時的網路爛梗；不為了搞笑扭曲事實。\n" +
        "- 維持台灣日常聊天的簡潔節奏；自我修正保留最後說法；可在適當處加最多 1 個貼切 Emoji。"

    // Legacy prompt texts for migration checks
    const val PRO_TEXT_V5 =
        "語氣：商務工作訊息，清晰得體，句尾依文意加適當標點；拿掉口語語助詞（啦、喔、欸），不加 emoji，原文本來就有的 emoji 保留。"
    const val CHAT_TEXT_V5 =
        "語氣：輕鬆口語，保留助詞（啦、喔、欸、哈哈）與自然斷句；句尾不強制加句號；可在適合處句尾加最多 1 個貼切 emoji（原已有則不加）；不將口語改為書面語。"
    const val CHAT_TEXT = CHAT_TEXT_V5
    const val PRO_TEXT_V4 =
        "語氣：專業、清楚、有禮，適合工作上的訊息；可以拿掉口語語助詞（啦、喔、欸），" +
            "但不要改成生硬的公文用語；不要加 emoji，原文本來就有的 emoji 保留。"
    const val CHAT_TEXT_V4 =
        "語氣：輕鬆自然的聊天訊息；保留口語語氣和語助詞（啦、喔、欸、耶、哈哈），不要改寫成書面語；" +
            "句子很短時可以省略句尾句號；可以在語氣適合的地方加上最多 1 個貼切的 emoji，放在句尾，" +
            "不要每句都加，也不要用 emoji 取代原本的文字；原文已經有 emoji 就不要再加；" +
            "加 emoji 不算擴寫。語氣規則和上面的規則衝突時，以語氣規則為準；" +
            "下面的「特別注意」永遠優先。"
    const val PRO_V4 = PRO_TEXT_V4
    const val CHAT_V4 = CHAT_TEXT_V4

    fun builtinAddon(id: String): String? = when (id) {
        TONE_BASIC -> BASIC_TEXT
        TONE_PRO -> PRO_TEXT
        TONE_EQ_CHAT -> EQ_CHAT_TEXT
        TONE_HUMOR_CHAT -> HUMOR_CHAT_TEXT
        TONE_CHAT -> EQ_CHAT_TEXT
        else -> null
    }

    val BASIC: String get() = BasePrompt.build(BASIC_TEXT)
    val PRO: String get() = BasePrompt.build(PRO_TEXT)
    val EQ_CHAT: String get() = BasePrompt.build(EQ_CHAT_TEXT)
    val HUMOR_CHAT: String get() = BasePrompt.build(HUMOR_CHAT_TEXT)
    val CHAT: String get() = EQ_CHAT

    fun defaultTones(proAddon: String = PRO_TEXT): List<Tone> = listOf(
        Tone(TONE_BASIC, "基礎整理", "基礎", "去贅字修標點，原汁原味", BASIC_TEXT, builtin = true, enabled = true, promptVersion = VERSION),
        Tone(TONE_PRO, "專業潤飾", "專業", "工作訊息，條理成熟", proAddon, builtin = true, enabled = true, promptVersion = VERSION),
        Tone(TONE_EQ_CHAT, "高情商聊天", "情商", "溫暖體貼，化解生硬", EQ_CHAT_TEXT, builtin = true, enabled = true, promptVersion = VERSION),
        Tone(TONE_HUMOR_CHAT, "幽默聊天", "幽默", "輕鬆風趣，帶梗不尬", HUMOR_CHAT_TEXT, builtin = true, enabled = true, promptVersion = VERSION)
    )
}

/**
 * Migration helper from earlier versions to v6.
 */
object ToneMigration {
    data class Outcome(val tones: List<Tone>, val polishTone: String)

    fun isUntouchedPro(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return true
        return prompt == TonePrompts.PRO ||
            prompt == TonePrompts.PRO_TEXT ||
            prompt == TonePrompts.PRO_TEXT_V5 ||
            prompt == TonePrompts.PRO_TEXT_V4 ||
            prompt == DEFAULT_POLISH_PROMPT ||
            prompt == LEGACY_POLISH_PROMPT_V4 ||
            prompt == LEGACY_POLISH_PROMPT_V2 ||
            prompt == LEGACY_POLISH_PROMPT_V1
    }

    fun isUntouchedChat(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return true
        return prompt == TonePrompts.EQ_CHAT ||
            prompt == TonePrompts.EQ_CHAT_TEXT ||
            prompt == TonePrompts.CHAT_TEXT_V5 ||
            prompt == TonePrompts.CHAT_TEXT_V4
    }

    fun migrate(oldPrompt: String?, polishEnabled: Boolean?): Outcome {
        val pro = if (isUntouchedPro(oldPrompt)) TonePrompts.PRO_TEXT else oldPrompt!!
        return Outcome(
            tones = TonePrompts.defaultTones(pro),
            polishTone = if (polishEnabled == false) TONE_OFF else TONE_BASIC
        )
    }

    fun upgradeTones(tones: List<Tone>): List<Tone> {
        val updated = tones.map { tone ->
            when (tone.id) {
                TONE_BASIC -> {
                    tone.copy(
                        name = "基礎整理",
                        short = "基礎",
                        desc = "去贅字修標點，原汁原味",
                        addonPrompt = TonePrompts.BASIC_TEXT,
                        enabled = true,
                        promptVersion = TonePrompts.VERSION
                    )
                }
                TONE_PRO -> {
                    if (isUntouchedPro(tone.addonPrompt)) {
                        tone.copy(
                            name = "專業潤飾",
                            short = "專業",
                            desc = "工作訊息，條理成熟",
                            addonPrompt = TonePrompts.PRO_TEXT,
                            promptVersion = TonePrompts.VERSION
                        )
                    } else {
                        tone
                    }
                }
                TONE_CHAT -> {
                    if (isUntouchedChat(tone.addonPrompt)) {
                        tone.copy(
                            id = TONE_EQ_CHAT,
                            name = "高情商聊天",
                            short = "情商",
                            desc = "溫暖體貼，化解生硬",
                            addonPrompt = TonePrompts.EQ_CHAT_TEXT,
                            promptVersion = TonePrompts.VERSION
                        )
                    } else {
                        tone.copy(id = TONE_EQ_CHAT)
                    }
                }
                TONE_EQ_CHAT -> {
                    if (isUntouchedChat(tone.addonPrompt)) {
                        tone.copy(
                            name = "高情商聊天",
                            short = "情商",
                            desc = "溫暖體貼，化解生硬",
                            addonPrompt = TonePrompts.EQ_CHAT_TEXT,
                            promptVersion = TonePrompts.VERSION
                        )
                    } else {
                        tone
                    }
                }
                TONE_HUMOR_CHAT -> {
                    tone.copy(
                        name = "幽默聊天",
                        short = "幽默",
                        desc = "輕鬆風趣，帶梗不尬",
                        addonPrompt = TonePrompts.HUMOR_CHAT_TEXT,
                        promptVersion = TonePrompts.VERSION
                    )
                }
                else -> tone
            }
        }.toMutableList()

        if (updated.none { it.id == TONE_BASIC }) {
            updated.add(0, Tone(TONE_BASIC, "基礎整理", "基礎", "去贅字修標點，原汁原味", TonePrompts.BASIC_TEXT, builtin = true, enabled = true, promptVersion = TonePrompts.VERSION))
        }
        if (updated.none { it.id == TONE_EQ_CHAT }) {
            updated.add(Tone(TONE_EQ_CHAT, "高情商聊天", "情商", "溫暖體貼，化解生硬", TonePrompts.EQ_CHAT_TEXT, builtin = true, enabled = true, promptVersion = TonePrompts.VERSION))
        }
        if (updated.none { it.id == TONE_HUMOR_CHAT }) {
            updated.add(Tone(TONE_HUMOR_CHAT, "幽默聊天", "幽默", "輕鬆風趣，帶梗不尬", TonePrompts.HUMOR_CHAT_TEXT, builtin = true, enabled = true, promptVersion = TonePrompts.VERSION))
        }

        return updated
    }
}
