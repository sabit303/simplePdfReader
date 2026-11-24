package com.simplepdf.reader.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

object SessionCodec {
    private const val VERSION = 2

    fun encode(session: ReaderSession): String {
        val tabs = JSONArray()
        session.tabs.forEach { tab ->
            val document = JSONObject()
                .put("path", tab.document.path)
                .put("title", tab.document.title)
                .put("modifiedAt", tab.document.modifiedAt)
                .put("sizeBytes", tab.document.sizeBytes)
            tabs.put(
                JSONObject()
                    .put("document", document)
                    .put("pageIndex", tab.pageIndex),
            )
        }
        val split = session.split?.let {
            JSONObject()
                .put("topPath", it.topPath)
                .put("bottomPath", it.bottomPath)
                .put("ratio", it.ratio.toDouble())
        } ?: JSONObject.NULL
        return JSONObject()
            .put("version", VERSION)
            .put("tabs", tabs)
            .put("selectedPath", session.selectedPath ?: JSONObject.NULL)
            .put("split", split)
            .toString()
    }

    fun decode(encoded: String): ReaderSession {
        try {
            val tokener = JSONTokener(encoded)
            val root = tokener.nextValue() as? JSONObject
                ?: throw IllegalArgumentException("A saved session must be a JSON object")
            require(tokener.nextClean() == '\u0000') { "Unexpected data after the saved session" }
            val version = root.requiredLong("version")
            require(version == 1L || version == VERSION.toLong()) {
                "Unsupported saved session version"
            }
            val savedTabs = root.get("tabs") as? JSONArray
                ?: throw IllegalArgumentException("Saved session tabs must be an array")
            val tabs = List(savedTabs.length()) { index ->
                val tab = savedTabs.get(index) as? JSONObject
                    ?: throw IllegalArgumentException("A saved tab must be an object")
                val document = tab.get("document") as? JSONObject
                    ?: throw IllegalArgumentException("A saved PDF must be an object")
                val pageIndex = tab.requiredLong("pageIndex")
                require(pageIndex in 0..Int.MAX_VALUE.toLong()) { "Invalid saved page index" }
                OpenPdfTab(
                    document = PdfDocument(
                        path = document.requiredString("path"),
                        title = document.requiredString("title"),
                        modifiedAt = document.requiredLong("modifiedAt"),
                        sizeBytes = document.requiredLong("sizeBytes"),
                    ),
                    pageIndex = pageIndex.toInt(),
                )
            }
            val selected = root.get("selectedPath")
            val selectedPath = if (selected === JSONObject.NULL) {
                null
            } else {
                selected as? String
                    ?: throw IllegalArgumentException("The selected PDF path must be a string or null")
            }
            val split = if (version == 1L) null else {
                when (val saved = root.get("split")) {
                    JSONObject.NULL -> null
                    is JSONObject -> ReaderSplit(
                        topPath = saved.requiredString("topPath"),
                        bottomPath = saved.requiredString("bottomPath"),
                        ratio = saved.requiredDouble("ratio").toFloat(),
                    )
                    else -> throw IllegalArgumentException("The saved split must be an object or null")
                }
            }
            return ReaderSession(tabs, selectedPath, split)
        } catch (exception: JSONException) {
            throw IllegalArgumentException("Malformed saved reader session", exception)
        }
    }

    private fun JSONObject.requiredString(key: String): String =
        get(key) as? String ?: throw IllegalArgumentException("$key must be a string")

    private fun JSONObject.requiredLong(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key must be a 64-bit integer" }
        return (value as Number).toLong()
    }

    private fun JSONObject.requiredDouble(key: String): Double {
        val value = get(key)
        require(value is Number) { "$key must be a number" }
        return (value as Number).toDouble().also { require(it.isFinite()) { "$key must be finite" } }
    }
}
