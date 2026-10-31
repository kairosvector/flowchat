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

import org.json.JSONArray
import org.json.JSONObject

/**
 * One AI polish tone (SPEC_r8 §14 / §15). [id] is stable ("pro", "chat",
 * "u_<millis>" for custom ones); the order of the list is the order shown
 * on the keyboard. [short] is the label on the tone key.
 */
data class Tone(
    val id: String,
    val name: String,
    val short: String,
    val desc: String,
    val prompt: String,
    val builtin: Boolean,
    val promptVersion: Int = TonePrompts.VERSION
)

/** The id the tone key stores when polishing is off. */
const val TONE_OFF = "off"
const val TONE_PRO = "pro"
const val TONE_CHAT = "chat"

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
                val prompt = o.optString("prompt", "")
                if (id.isEmpty() || id == TONE_OFF || prompt.isBlank()) return@mapNotNull null
                val name = o.optString("name", "").ifBlank { id }
                Tone(
                    id = id,
                    name = name,
                    short = o.optString("short", "").ifBlank { ToneRules.defaultShort(name) },
                    desc = o.optString("desc", ""),
                    prompt = prompt,
                    builtin = o.optBoolean("builtin", false),
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
                    .put("prompt", tone.prompt)
                    .put("builtin", tone.builtin)
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

    /** True when the prompt still has the anti-injection wording (the <原文> tag and 特別注意). */
    fun hasInjectionGuard(prompt: String): Boolean = prompt.contains("<原文>") && prompt.contains("特別注意")
}

/**
 * The two built-in prompts. Both are the v19 default prompt, word for word,
 * with one tone paragraph inserted right before 「特別注意」 (SPEC_r8 §14).
 */
object TonePrompts {
    const val VERSION = 4

    private const val GUARD_MARK = "特別注意："

    const val PRO_TEXT =
        "語氣：專業、清楚、有禮，適合工作上的訊息；可以拿掉口語語助詞（啦、喔、欸），" +
            "但不要改成生硬的公文用語；不要加 emoji，原文本來就有的 emoji 保留。"

    const val CHAT_TEXT =
        "語氣：輕鬆自然的聊天訊息；保留口語語氣和語助詞（啦、喔、欸、耶、哈哈），不要改寫成書面語；" +
            "句子很短時可以省略句尾句號；可以在語氣適合的地方加上最多 1 個貼切的 emoji，放在句尾，" +
            "不要每句都加，也不要用 emoji 取代原本的文字；原文已經有 emoji 就不要再加；" +
            "加 emoji 不算擴寫。語氣規則和上面的規則衝突時，以語氣規則為準；" +
            "下面的「特別注意」永遠優先。"

    /** [basePrompt] with [toneText] put in front of 「特別注意」. */
    fun build(basePrompt: String, toneText: String): String {
        val at = basePrompt.indexOf(GUARD_MARK)
        require(at >= 0) { "the base prompt has no 特別注意 paragraph" }
        return basePrompt.substring(0, at) + toneText + basePrompt.substring(at)
    }

    val PRO: String get() = build(DEFAULT_POLISH_PROMPT, PRO_TEXT)
    val CHAT: String get() = build(DEFAULT_POLISH_PROMPT, CHAT_TEXT)

    fun builtinPrompt(id: String): String? = when (id) {
        TONE_PRO -> PRO
        TONE_CHAT -> CHAT
        else -> null
    }

    /** The two tones a fresh install (or a migration) starts with. */
    fun defaultTones(proPrompt: String = PRO): List<Tone> = listOf(
        Tone(TONE_PRO, "專業", "專業", "工作訊息", proPrompt, builtin = true),
        Tone(TONE_CHAT, "聊天", "聊天", "口語＋emoji", CHAT, builtin = true)
    )
}

/**
 * The one-time move from the single `llm-prompt` + `polish-enabled` settings
 * to `tones-json` + `polish-tone` (SPEC_r8 §15). Pure, so every case is a unit test.
 */
object ToneMigration {
    data class Outcome(val tones: List<Tone>, val polishTone: String)

    /**
     * [oldPrompt] is the saved `llm-prompt` (null = never saved), [polishEnabled]
     * the saved switch (null = never saved). A prompt that is empty or equals a
     * default of an earlier version was never edited and becomes the new 專業
     * default; an edited one is kept as the 專業 instruction. 聊天 is always the
     * new default. Polishing off becomes the tone "off", otherwise 專業.
     */
    fun migrate(oldPrompt: String?, polishEnabled: Boolean?): Outcome {
        val untouched = oldPrompt == null || oldPrompt.isBlank() ||
            oldPrompt == DEFAULT_POLISH_PROMPT ||
            oldPrompt == LEGACY_POLISH_PROMPT_V2 ||
            oldPrompt == LEGACY_POLISH_PROMPT_V1
        val pro = if (untouched) TonePrompts.PRO else oldPrompt!!
        return Outcome(
            tones = TonePrompts.defaultTones(pro),
            polishTone = if (polishEnabled == false) TONE_OFF else TONE_PRO
        )
    }
}
