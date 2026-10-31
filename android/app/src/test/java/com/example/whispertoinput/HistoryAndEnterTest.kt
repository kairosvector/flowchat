package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 11a: the 20-record history file. */
class HistoryAndEnterTest {
    private fun record(n: Int, polished: String? = null) =
        TranscriptionRecord(1000L + n, "raw $n", polished)

    @Test
    fun theOldSingleObjectFileIsStillRead() {
        val old = """{"timestampMillis":1730368000000,"rawText":"約後天","polishedText":"約後天。"}"""
        val records = HistoryCodec.parse(old)
        assertEquals(1, records.size)
        assertEquals("約後天", records[0].rawText)
        assertEquals("約後天。", records[0].polishedText)
        assertEquals(1730368000000L, records[0].timestampMillis)
    }

    @Test
    fun theOldFileWithANullPolishedTextIsStillRead() {
        val old = """{"timestampMillis":5,"rawText":"hello","polishedText":null}"""
        val records = HistoryCodec.parse(old)
        assertEquals(1, records.size)
        assertNull(records[0].polishedText)
    }

    @Test
    fun anArrayRoundTripsInOrder() {
        val list = listOf(record(3, "p3"), record(2), record(1))
        val back = HistoryCodec.parse(HistoryCodec.encode(list))
        assertEquals(list, back)
    }

    @Test
    fun garbageAndEmptyFilesGiveAnEmptyList() {
        assertTrue(HistoryCodec.parse("").isEmpty())
        assertTrue(HistoryCodec.parse("not json").isEmpty())
        assertTrue(HistoryCodec.parse("[]").isEmpty())
        assertTrue(HistoryCodec.parse("""{"rawText":""}""").isEmpty())
    }

    @Test
    fun theNewestRecordGoesFirst() {
        val added = HistoryCodec.add(listOf(record(1)), record(2))
        assertEquals(listOf(record(2), record(1)), added)
    }

    @Test
    fun atMostTwentyRecordsAreKept() {
        var list = emptyList<TranscriptionRecord>()
        for (n in 1..25) list = HistoryCodec.add(list, record(n))
        assertEquals(20, list.size)
        assertEquals("raw 25", list.first().rawText)
        assertEquals("raw 6", list.last().rawText)
        assertEquals(20, HistoryCodec.parse(HistoryCodec.encode(list)).size)
    }

    @Test
    fun aRePolishOfTheSameTranscriptReplacesTheNewestRecord() {
        val first = HistoryCodec.add(listOf(record(1)), record(2))
        val again = HistoryCodec.add(first, TranscriptionRecord(9999L, "raw 2", "polished again"))
        assertEquals(2, again.size)
        assertEquals("polished again", again[0].polishedText)
        assertEquals("raw 1", again[1].rawText)
    }
}
