package com.simplepdf.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.simplepdf.reader.data.PdfDocument
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DocumentImporter(private val context: Context) {
    private val directory = File(context.filesDir, "opened-pdfs")
    private val metadata = context.getSharedPreferences("imported-pdfs", Context.MODE_PRIVATE)

    fun documents(): List<PdfDocument> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("Cannot read imported PDFs.")
        return files.filter { it.extension == "pdf" && it.isFile }.map { file ->
            PdfDocument(
                file.absolutePath,
                metadata.getString(file.name, file.name) ?: file.name,
                metadata.getLong("${file.name}.modified", file.lastModified()),
                file.length(),
            )
        }
    }

    fun import(uri: Uri): PdfDocument {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "Missing file path." }).canonicalFile
            require(file.isFile && file.canRead()) { "This PDF is not accessible." }
            return PdfDocument(file.path, file.name, file.lastModified(), file.length())
        }
        require(uri.scheme == "content") { "Unsupported document URI." }
        var title = context.getString(R.string.untitled_pdf)
        var modified = System.currentTimeMillis()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) title = cursor.getString(nameColumn)
                val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                    cursor.getLong(modifiedColumn).takeIf { it > 0 }?.let { modified = it }
                }
            }
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create PDF storage.")
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.pdf")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var complete = false
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read this PDF.")
            input.use { it.copyTo(output) }
            atomic.finishWrite(output)
            complete = true
        } finally {
            if (!complete) atomic.failWrite(output)
        }
        if (!metadata.edit().putString(file.name, title)
                .putLong("${file.name}.modified", modified).commit()) {
            throw IOException("Cannot save the imported PDF details.")
        }
        return PdfDocument(file.absolutePath, title, modified, file.length())
    }
}
