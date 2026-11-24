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

data class ReaderSplit(
    val topPath: String,
    val bottomPath: String,
    val ratio: Float = 0.5f,
) {
    init {
        require(topPath != bottomPath) { "A split must show two different PDFs" }
        require(ratio.isFinite() && ratio > 0f && ratio < 1f) {
            "A split ratio must be between zero and one"
        }
    }

    fun contains(path: String): Boolean = path == topPath || path == bottomPath

    fun other(path: String): String {
        require(contains(path)) { "The PDF must be visible in the split" }
        return if (path == topPath) bottomPath else topPath
    }
}

data class ReaderSession(
    val tabs: List<OpenPdfTab> = emptyList(),
    val selectedPath: String? = null,
    val split: ReaderSplit? = null,
) {
    init {
        require(tabs.map { it.document.path }.distinct().size == tabs.size) {
            "A PDF may only have one open tab"
        }
        require(selectedPath == null || tabs.any { it.document.path == selectedPath }) {
            "The selected PDF must have an open tab"
        }
        require(split == null || selectedPath != null) { "A split requires a selected PDF" }
        require(split == null || split.topPath == selectedPath || split.bottomPath == selectedPath) {
            "The selected PDF must be visible in the split"
        }
        require(split == null || tabs.any { it.document.path == split.topPath }) {
            "The top split PDF must have an open tab"
        }
        require(split == null || tabs.any { it.document.path == split.bottomPath }) {
            "The bottom split PDF must have an open tab"
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
        return copy(selectedPath = path, split = null)
    }

    fun splitWith(path: String, droppedOnTop: Boolean): ReaderSession {
        val current = requireNotNull(selectedPath) { "A PDF must be selected before opening a split" }
        require(path != current) { "A PDF cannot be split with itself" }
        require(tabs.any { it.document.path == path }) { "The split PDF must have an open tab" }
        val split = if (droppedOnTop) ReaderSplit(path, current) else ReaderSplit(current, path)
        return copy(split = split)
    }

    fun updateSplitRatio(ratio: Float): ReaderSession {
        val current = requireNotNull(split) { "A split must be open before resizing it" }
        return copy(split = current.copy(ratio = ratio))
    }

    fun collapseSplit(path: String = requireNotNull(selectedPath)): ReaderSession {
        val current = requireNotNull(split) { "A split must be open before collapsing it" }
        require(current.contains(path)) { "The collapsed PDF must be visible in the split" }
        return copy(selectedPath = current.other(path), split = null)
    }

    fun clearSplit(): ReaderSession = copy(split = null)

    fun close(path: String): ReaderSession {
        val index = tabs.indexOfFirst { it.document.path == path }
        require(index >= 0) { "The PDF to close must have an open tab" }
        val remaining = tabs.filterIndexed { tabIndex, _ -> tabIndex != index }
        val nextSelection = if (split?.contains(path) == true) {
            split.other(path)
        } else if (selectedPath == path) {
            remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.document?.path
        } else {
            selectedPath
        }
        val remainingSplit = split?.takeUnless { it.contains(path) }
        return ReaderSession(remaining, nextSelection, remainingSplit)
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
