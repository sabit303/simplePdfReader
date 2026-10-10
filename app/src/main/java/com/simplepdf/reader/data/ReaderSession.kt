package com.simplepdf.reader.data

data class PdfDocument(
    val path: String,
    val title: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
) {
    init {
        require(path.isNotBlank()) { "A PDF path must not be blank" }
        require(sizeBytes >= 0) { "A PDF size must not be negative" }
    }
}

data class OpenPdfTab(
    val document: PdfDocument,
    val pageIndex: Int = 0,
) {
    init {
        require(pageIndex >= 0) { "A page index must not be negative" }
    }
}

data class ReaderSession(
    val tabs: List<OpenPdfTab> = emptyList(),
    val selectedPath: String? = null,
) {
    init {
        require(tabs.map { it.document.path }.distinct().size == tabs.size) {
            "A PDF may only have one open tab"
        }
        require(selectedPath == null || tabs.any { it.document.path == selectedPath }) {
            "The selected PDF must have an open tab"
        }
    }

    fun open(document: PdfDocument): ReaderSession {
        val index = tabs.indexOfFirst { it.document.path == document.path }
        val updatedTabs = if (index < 0) {
            tabs + OpenPdfTab(document)
        } else {
            tabs.mapIndexed { tabIndex, tab ->
                if (tabIndex == index) tab.copy(document = document) else tab
            }
        }
        return ReaderSession(updatedTabs, document.path)
    }

    fun select(path: String?): ReaderSession {
        require(path == null || tabs.any { it.document.path == path }) {
            "The selected PDF must have an open tab"
        }
        return copy(selectedPath = path)
    }

    fun close(path: String): ReaderSession {
        val index = tabs.indexOfFirst { it.document.path == path }
        require(index >= 0) { "The PDF to close must have an open tab" }
        val remaining = tabs.filterIndexed { tabIndex, _ -> tabIndex != index }
        val nextSelection = if (selectedPath == path) {
            remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.document?.path
        } else {
            selectedPath
        }
        return ReaderSession(remaining, nextSelection)
    }

    fun updatePage(path: String, pageIndex: Int): ReaderSession {
        require(pageIndex >= 0) { "A page index must not be negative" }
        require(tabs.any { it.document.path == path }) {
            "The PDF to update must have an open tab"
        }
        return copy(tabs = tabs.map { tab ->
            if (tab.document.path == path) tab.copy(pageIndex = pageIndex) else tab
        })
    }
}
