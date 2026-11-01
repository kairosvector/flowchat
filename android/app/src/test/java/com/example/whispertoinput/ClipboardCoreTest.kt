package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 12: the clipboard rules (SPEC_r8 §13). Only made-up test values are used. */
class ClipboardCoreTest {
    private val now = 1_730_368_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun kind(text: String, flag: Boolean = false) = ClipClassifier.classify(text, flag)

    // ---- §13.3, in the order of the spec ----

    @Test
    fun theSensitiveFlagMasksAnythingEvenAVerificationCode() {
        assertEquals(ClipKind.SENSITIVE, kind("明天下午三點開會", flag = true))
        assertEquals(ClipKind.SENSITIVE, kind("482913", flag = true))
        assertTrue(ClipKind.SENSITIVE.masked)
    }

    @Test
    fun aVerificationCodeIsFourToEightDigitsWithOrWithoutSpaces() {
        for (text in listOf("482913", "4829", "12345678", "482 913", " 482913 \n")) {
            assertEquals(text, ClipKind.OTP, kind(text))
        }
        assertEquals(ClipKind.TEXT, kind("123"))
        assertEquals(ClipKind.TEXT, kind("123456789"))       // nine digits are not a code
        assertEquals(ClipKind.TEXT, kind("12a456"))
    }

    @Test
    fun aVerificationCodeInASentenceNeedsTheKeywordAndAFourToEightDigitNumber() {
        assertEquals(ClipKind.OTP, kind("您的驗證碼是 482913，請勿告知他人"))
        assertEquals(ClipKind.OTP, kind("認證碼：7788"))
        assertEquals(ClipKind.OTP, kind("Your OTP is 556677"))
        assertEquals(ClipKind.OTP, kind("Your verification code: 123456"))
        assertEquals(ClipKind.TEXT, kind("您的驗證碼已寄出"))               // keyword, no number
        assertEquals(ClipKind.TEXT, kind("我的電話是 482913 號"))          // number, no keyword
        assertEquals(ClipKind.TEXT, kind("驗證碼 123456789012"))          // the number is too long
    }

    @Test
    fun aVerificationCodeIsShownAsItIsButNeverKept() {
        assertFalse(ClipKind.OTP.masked)
        assertFalse(ClipKind.OTP.persistable)
    }

    @Test
    fun aCardNumberMustPassTheLuhnCheck() {
        assertTrue(ClipClassifier.luhn("4111111111111111"))
        assertTrue(ClipClassifier.luhn("5500005555555559"))
        assertTrue(ClipClassifier.luhn("378282246310005"))
        assertFalse(ClipClassifier.luhn("4111111111111112"))
        assertEquals(ClipKind.CARD, kind("4111 1111 1111 1111"))
        assertEquals(ClipKind.CARD, kind("4111-1111-1111-1111"))
        assertEquals(ClipKind.CARD, kind("378282246310005"))              // 15 digits
        assertEquals(ClipKind.TEXT, kind("4111 1111 1111 1112"))           // wrong check digit
        assertEquals(ClipKind.TEXT, kind("411111111111"))                  // 12 digits: too short
        assertEquals(ClipKind.TEXT, kind("41111111111111111111"))          // 20 digits: too long
    }

    @Test
    fun aPasswordOrKeyHasNoSpacesIsEightToSixtyFourLongAndHasThreeKindsOfCharacters() {
        for (text in listOf("Abcd1234!", "Tr0ub4dor&3", "aB3\$xY9z", "x".repeat(10) + "A1")) {
            assertEquals(text, ClipKind.PASSWORD, kind(text))
        }
        assertEquals(ClipKind.TEXT, kind("password"))                      // one kind
        assertEquals(ClipKind.TEXT, kind("Password"))                      // two kinds
        assertEquals(ClipKind.TEXT, kind("password123"))                   // two kinds
        assertEquals(ClipKind.TEXT, kind("Ab1!"))                          // too short
        assertEquals(ClipKind.TEXT, kind("Abcd 1234!"))                    // has a space
        assertEquals(ClipKind.TEXT, kind("Aa1!" + "x".repeat(61)))         // 65 characters
    }

    @Test
    fun aKnownKeyPrefixIsEnoughForAKey() {
        assertEquals(ClipKind.PASSWORD, kind("sk-testfakekey000000"))
        assertEquals(ClipKind.PASSWORD, kind("gsk_test_xxxx3f9a"))
        assertEquals(ClipKind.PASSWORD, kind("ghp_fakefakefake0000"))
        assertEquals(ClipKind.PASSWORD, kind("AKIAFAKEFAKEFAKE0000"))
        assertEquals(ClipKind.PASSWORD, kind("xoxb-fake-token"))
        assertEquals(ClipKind.PASSWORD, kind("sk-short"))                  // eight characters and a prefix
        assertEquals(ClipKind.TEXT, kind("sk-abc"))                        // shorter than eight
    }

    @Test
    fun aTaiwanIdNumberIsMasked() {
        assertEquals(ClipKind.ID_NUMBER, kind("A123456789"))
        assertEquals(ClipKind.ID_NUMBER, kind("F223456789"))
        assertEquals(ClipKind.TEXT, kind("A323456789"))                    // the second character is 1 or 2
        assertEquals(ClipKind.TEXT, kind("a123456789"))
        assertEquals(ClipKind.TEXT, kind("A12345678"))
    }

    @Test
    fun ordinaryTextAndWebAddressesAreNotMistakenForSecrets() {
        assertEquals(ClipKind.TEXT, kind("明天下午三點在信義區開會，記得帶合約"))
        assertEquals(ClipKind.TEXT, kind("hello world"))
        assertEquals(ClipKind.TEXT, kind("今天的會議紀錄已經整理好了"))
        assertEquals(ClipKind.TEXT, kind("john.doe@example.com"))
        // upper + lower + digits + symbols, yet it is an address, not a password
        assertEquals(ClipKind.URL, kind("https://maps.app.goo.gl/x7Op2"))
        assertEquals(ClipKind.URL, kind("http://Example.com/Path?Id=42&Q=A"))
        assertTrue(ClipKind.URL.persistable)
        assertFalse(ClipKind.URL.masked)
        assertTrue(ClipKind.TEXT.persistable)
    }

    @Test
    fun onlyTheFourMaskedKindsAreMasked() {
        assertEquals(
            setOf(ClipKind.SENSITIVE, ClipKind.CARD, ClipKind.PASSWORD, ClipKind.ID_NUMBER),
            ClipKind.values().filter { it.masked }.toSet()
        )
        assertEquals(setOf(ClipKind.TEXT, ClipKind.URL), ClipKind.values().filter { it.persistable }.toSet())
    }

    // ---- the one-line preview ----

    @Test
    fun thePreviewMergesWhitespaceAndKeepsFortyCharacters() {
        assertEquals("a b c", ClipPreview.of("a   b\n\n c  "))
        assertEquals("第一行 第二行", ClipPreview.of("第一行\n\n   第二行"))
        val long = "字".repeat(60)
        assertEquals(40, ClipPreview.of(long).length)
        assertEquals("字".repeat(40), ClipPreview.of(long))
        assertEquals("短", ClipPreview.of("短"))
        assertEquals("", ClipPreview.of("   \n "))
    }

    @Test
    fun thePreviewDoesNotCutAnEmojiInHalf() {
        val text = "😀".repeat(50)
        val preview = ClipPreview.of(text)
        assertEquals(40, preview.codePointCount(0, preview.length))
    }

    // ---- clip-history-json ----

    @Test
    fun theHistoryRoundTripsNewestFirst() {
        val list = listOf(ClipRecord("乙", now), ClipRecord("甲", now - minute))
        assertEquals(list, ClipHistory.parse(ClipHistory.encode(list), now))
    }

    @Test
    fun theHistoryKeepsFiveEntries() {
        var list = emptyList<ClipRecord>()
        for (n in 1..25) list = ClipHistory.add(list, "item $n", now + n, now + n)
        assertEquals(5, list.size)
        assertEquals("item 25", list.first().text)
        assertEquals("item 21", list.last().text)
        assertEquals(5, ClipHistory.parse(ClipHistory.encode(list), now + 25).size)
    }

    @Test
    fun theHistoryDropsEntriesOlderThanADayWhenItIsRead() {
        val json = ClipHistory.encode(
            listOf(
                ClipRecord("new", now),
                ClipRecord("edge", now - ClipHistory.TTL_MILLIS),
                ClipRecord("old", now - ClipHistory.TTL_MILLIS - 1)
            )
        )
        assertEquals(listOf("new", "edge"), ClipHistory.parse(json, now).map { it.text })
    }

    @Test
    fun addingTheSameTextMovesItToTheFrontOnce() {
        var list = ClipHistory.add(emptyList(), "甲", now - 3 * minute, now)
        list = ClipHistory.add(list, "乙", now - 2 * minute, now)
        list = ClipHistory.add(list, "甲", now, now)
        assertEquals(listOf("甲", "乙"), list.map { it.text })
        assertEquals(now, list.first().timestampMillis)
    }

    @Test
    fun aBrokenHistoryFileReadsAsEmpty() {
        assertTrue(ClipHistory.parse(null, now).isEmpty())
        assertTrue(ClipHistory.parse("", now).isEmpty())
        assertTrue(ClipHistory.parse("not json", now).isEmpty())
        assertTrue(ClipHistory.parse("[{\"text\":\"\"}]", now).isEmpty())
    }

    @Test
    fun sensitiveClipsAndCodesNeverGoIntoTheHistoryFile() {
        // the rule the service follows: only persistable kinds are added to ClipHistory
        val inputs = listOf("4111 1111 1111 1111", "sk-testfakekey000000", "482913", "A123456789", "普通的一句話")
        var list = emptyList<ClipRecord>()
        for (t in inputs) {
            if (ClipClassifier.classify(t, false).persistable) list = ClipHistory.add(list, t, now, now)
        }
        assertEquals(listOf("普通的一句話"), list.map { it.text })
        val flagged = ClipClassifier.classify("一句話", sensitiveFlag = true)
        assertFalse(flagged.persistable)
        val json = ClipHistory.encode(list)
        for (t in inputs.take(4)) assertFalse(json.contains(t))
    }

    // ---- memory-only clips ----

    @Test
    fun aMemoryClipLivesTwoMinutes() {
        val list = ClipMemory.add(emptyList(), "482913", now, ClipKind.OTP, now)
        assertEquals(1, ClipMemory.purge(list, now + 2 * minute, false).size)
        assertTrue(ClipMemory.purge(list, now + 2 * minute + 1, false).isEmpty())
    }

    @Test
    fun maskedClipsAreDroppedWhenTheKeyboardClosesButACodeStaysUntilItExpires() {
        var list = ClipMemory.add(emptyList(), "4111 1111 1111 1111", now, ClipKind.CARD, now)
        list = ClipMemory.add(list, "482913", now, ClipKind.OTP, now)
        val after = ClipMemory.purge(list, now + 10_000, keyboardClosed = true)
        assertEquals(listOf("482913"), after.map { it.text })
    }

    // ---- the list on the 📋 tab ----

    @Test
    fun theListStartsWithTheCurrentClipboardAndHasEachTextOnce() {
        val current = ClipItem("甲", now, ClipKind.TEXT, true)
        val history = listOf(ClipRecord("甲", now - minute), ClipRecord("乙", now - 2 * minute))
        val memory = listOf(MemoryClip("482913", now - 30_000, ClipKind.OTP))
        val items = ClipList.build(current, history, memory, now)
        assertEquals(listOf("甲", "482913", "乙"), items.map { it.text })
        assertTrue(items.first().isCurrent)
        assertEquals(ClipKind.OTP, items[1].kind)
    }

    @Test
    fun anExpiredMemoryClipIsNotListedAndWithoutAClipboardTheListStartsWithHistory() {
        val memory = listOf(MemoryClip("482913", now - 3 * minute, ClipKind.OTP))
        val items = ClipList.build(null, listOf(ClipRecord("乙", now)), memory, now)
        assertEquals(listOf("乙"), items.map { it.text })
        assertFalse(items.first().isCurrent)
    }

    @Test
    fun aHistoryEntryThatIsAnAddressIsListedAsOne() {
        val items = ClipList.build(null, listOf(ClipRecord("https://maps.app.goo.gl/x7Op2", now)), emptyList(), now)
        assertEquals(ClipKind.URL, items.single().kind)
    }

    @Test
    fun nonPersistableCurrentClipExpiresAfterTwoMinutesInList() {
        // OTP older than 2 minutes is hidden even if it is currently in system clipboard
        val expiredOtp = ClipItem("482913", now - 2 * minute - 1, ClipKind.OTP, true)
        val list1 = ClipList.build(expiredOtp, listOf(ClipRecord("乙", now)), emptyList(), now)
        assertEquals(listOf("乙"), list1.map { it.text })

        // OTP within 2 minutes is shown
        val freshOtp = ClipItem("482913", now - minute, ClipKind.OTP, true)
        val list2 = ClipList.build(freshOtp, listOf(ClipRecord("乙", now)), emptyList(), now)
        assertEquals(listOf("482913", "乙"), list2.map { it.text })

        // Masked/sensitive card older than 2 minutes is hidden
        val expiredCard = ClipItem("4111 1111 1111 1111", now - 2 * minute - 1, ClipKind.CARD, true)
        val list3 = ClipList.build(expiredCard, listOf(ClipRecord("乙", now)), emptyList(), now)
        assertEquals(listOf("乙"), list3.map { it.text })

        // Ordinary text older than 2 minutes stays visible as current clipboard
        val oldText = ClipItem("old text", now - 10 * minute, ClipKind.TEXT, true)
        val list4 = ClipList.build(oldText, listOf(ClipRecord("乙", now)), emptyList(), now)
        assertEquals(listOf("old text", "乙"), list4.map { it.text })
    }

    // ---- the paste chip ----

    private fun item(text: String, ts: Long) = ClipItem(text, ts, ClipClassifier.classify(text, false), true)

    @Test
    fun theChipShowsForAClipCopiedWithinTwoMinutes() {
        assertTrue(PasteChip.visible(item("甲", now - 119_000), now, emptySet(), null))
        assertTrue(PasteChip.visible(item("甲", now - 2 * minute), now, emptySet(), null))
        assertFalse(PasteChip.visible(item("甲", now - 2 * minute - 1), now, emptySet(), null))
        assertFalse(PasteChip.visible(null, now, emptySet(), null))
    }

    @Test
    fun aPastedOrDismissedClipDoesNotComeBack() {
        val clip = item("甲", now - 10_000)
        val key = PasteChip.key(clip.text, clip.timestampMillis)
        assertFalse(PasteChip.visible(clip, now, setOf(key), null))
        assertFalse(PasteChip.visible(clip, now, emptySet(), key))
        // another copy of the same text a moment later is a new clip
        assertTrue(PasteChip.visible(item("甲", now - 1_000), now, setOf(key), key))
    }

    @Test
    fun theKeyHidesTheText() {
        val key = PasteChip.key("my secret text", 123L)
        assertFalse(key.contains("secret"))
        assertTrue(key.endsWith(":123"))
        assertEquals(64 + 4, key.length)
    }

    // ---- "上次" chip (SPEC_r8 §22.4) ----

    @Test
    fun lastTranscriptionChipShownWhenFieldChangesWithinThirtyMinutes() {
        val baseNow = 1_000_000L
        val ts = baseNow - 5 * 60 * 1000L // 5 mins ago

        val canShow = LastTranscriptionChip.canShow(
            isVoiceTab = true,
            isIdle = true,
            noOverlay = true,
            isStatusDefaultEmpty = true,
            pasteChipVisible = false,
            isDismissed = false,
            isPasswordField = false,
            isNoPersonalizedLearning = false,
            currentPackage = "com.android.chrome",
            currentFieldId = 101,
            lastPackage = "jp.naver.line.android",
            lastFieldId = 202,
            lastText = "你好這是一段測試語音",
            lastTimestamp = ts,
            now = baseNow
        )
        assertTrue(canShow)
    }

    @Test
    fun lastTranscriptionChipHiddenInSameField() {
        val baseNow = 1_000_000L
        val ts = baseNow - 60_000L

        val canShow = LastTranscriptionChip.canShow(
            isVoiceTab = true,
            isIdle = true,
            noOverlay = true,
            isStatusDefaultEmpty = true,
            pasteChipVisible = false,
            isDismissed = false,
            isPasswordField = false,
            isNoPersonalizedLearning = false,
            currentPackage = "com.android.chrome",
            currentFieldId = 101,
            lastPackage = "com.android.chrome",
            lastFieldId = 101,
            lastText = "你好",
            lastTimestamp = ts,
            now = baseNow
        )
        assertFalse(canShow)
    }

    @Test
    fun lastTranscriptionChipHiddenWhenExpiredOrSensitiveOrDismissed() {
        val baseNow = 1_000_000L
        val expiredTs = baseNow - 31 * 60 * 1000L // 31 mins ago

        // Expired > 30 mins
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = true, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = false, isDismissed = false, isPasswordField = false, isNoPersonalizedLearning = false,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = expiredTs, now = baseNow
            )
        )

        val validTs = baseNow - 60_000L
        // Password field
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = true, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = false, isDismissed = false, isPasswordField = true, isNoPersonalizedLearning = false,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = validTs, now = baseNow
            )
        )

        // No personalized learning
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = true, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = false, isDismissed = false, isPasswordField = false, isNoPersonalizedLearning = true,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = validTs, now = baseNow
            )
        )

        // Paste chip has higher priority
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = true, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = true, isDismissed = false, isPasswordField = false, isNoPersonalizedLearning = false,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = validTs, now = baseNow
            )
        )

        // Dismissed
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = true, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = false, isDismissed = true, isPasswordField = false, isNoPersonalizedLearning = false,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = validTs, now = baseNow
            )
        )

        // Not Voice tab
        assertFalse(
            LastTranscriptionChip.canShow(
                isVoiceTab = false, isIdle = true, noOverlay = true, isStatusDefaultEmpty = true,
                pasteChipVisible = false, isDismissed = false, isPasswordField = false, isNoPersonalizedLearning = false,
                currentPackage = "com.b", currentFieldId = 2, lastPackage = "com.a", lastFieldId = 1,
                lastText = "測試", lastTimestamp = validTs, now = baseNow
            )
        )
    }
}
