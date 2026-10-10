package com.simplepdf.reader.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCodecTest {
    private val first = PdfDocument("/storage/日本語 \"book\".pdf", "日本語 \"book\".pdf", 1234, 5678)
    private val second = PdfDocument("/private/imported.pdf", "Imported\nPDF", Long.MAX_VALUE, 0)
    private val session = ReaderSession(
        tabs = listOf(OpenPdfTab(first, 12), OpenPdfTab(second, Int.MAX_VALUE)),
        selectedPath = second.path,
    )

    @Test
    fun roundTripPreservesMetadataOrderSelectionAndPages() {
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        assertEquals(session.select(null), SessionCodec.decode(SessionCodec.encode(session.select(null))))
        assertEquals(ReaderSession(), SessionCodec.decode(SessionCodec.encode(ReaderSession())))
        val historicalDocument = ReaderSession().open(first.copy(modifiedAt = -1000))
        assertEquals(historicalDocument, SessionCodec.decode(SessionCodec.encode(historicalDocument)))
    }

    @Test
    fun encodedFormatHasAnExplicitVersion() {
        val encoded = JSONObject(SessionCodec.encode(session))
        assertEquals(1, encoded.getInt("version"))
    }

    @Test
    fun unsupportedAndMissingVersionsAreRejected() {
        for (version in listOf(-1, 0, 2, "1", JSONObject.NULL)) {
            rejects { put("version", version) }
        }
        rejects { remove("version") }
    }

    @Test
    fun malformedJsonAndTrailingDataAreRejected() {
        for (encoded in listOf("", "{", "null", "[]", "{}", "not json", "{} trailing")) {
            assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(SessionCodec.encode(session) + "{}")
        }
    }

    @Test
    fun missingAndWrongFieldTypesAreNotSilentlyDefaulted() {
        rejects { remove("tabs") }
        rejects { put("tabs", JSONObject.NULL) }
        rejects { put("tabs", JSONObject()) }
        rejects { remove("selectedPath") }
        rejects { put("selectedPath", false) }
        rejects { put("tabs", JSONArray().put("not an object")) }
        rejects { getJSONArray("tabs").getJSONObject(0).remove("document") }
        rejects { getJSONArray("tabs").getJSONObject(0).put("document", "not an object") }
        rejects { getJSONArray("tabs").getJSONObject(0).remove("pageIndex") }
        for (field in listOf("path", "title", "modifiedAt", "sizeBytes")) {
            rejects { document().remove(field) }
            rejects { document().put(field, JSONObject.NULL) }
        }
        rejects { document().put("path", 10) }
        rejects { document().put("title", true) }
        rejects { document().put("modifiedAt", "1234") }
        rejects { document().put("sizeBytes", 0.5) }
    }

    @Test
    fun invalidPagesMetadataAndSessionInvariantsAreRejected() {
        for (page in listOf(-1, Int.MAX_VALUE.toLong() + 1, "12", 1.5, JSONObject.NULL)) {
            rejects { getJSONArray("tabs").getJSONObject(0).put("pageIndex", page) }
        }
        rejects { document().put("path", "") }
        rejects { document().put("sizeBytes", -1) }
        rejects { put("selectedPath", "/missing.pdf") }
        rejects { getJSONArray("tabs").put(getJSONArray("tabs").getJSONObject(0)) }
    }

    @Test
    fun oversizedIntegerIsRejectedRatherThanTruncated() {
        val encoded = SessionCodec.encode(session).replace(
            "\"sizeBytes\":5678",
            "\"sizeBytes\":9223372036854775808",
        )
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded) }
    }

    private fun rejects(change: JSONObject.() -> Unit) {
        val encoded = JSONObject(SessionCodec.encode(session)).apply(change).toString()
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded) }
    }

    private fun JSONObject.document(): JSONObject =
        getJSONArray("tabs").getJSONObject(0).getJSONObject("document")
}
