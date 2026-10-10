package com.simplepdf.reader.data

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.CancellationException

data class ScanResult(
    val documents: List<PdfDocument>,
    val unreadableDirectories: Int,
)

object PdfScanner {
    fun scan(
        roots: List<File>,
        isCancelled: () -> Boolean = { false },
    ): ScanResult {
        val documents = mutableMapOf<String, PdfDocument>()
        val visitedDirectories = mutableSetOf<Path>()
        val unreadableDirectories = mutableSetOf<Path>()

        fun checkCancellation() {
            if (isCancelled()) throw CancellationException("PDF scanning was cancelled")
        }

        fun recordUnreadable(path: Path) {
            unreadableDirectories.add(path.toAbsolutePath().normalize())
        }

        checkCancellation()
        for (root in roots) {
            checkCancellation()
            val rootPath = root.toPath().toAbsolutePath().normalize()
            if (isExcludedDirectory(rootPath)) continue
            val realRoot = try {
                rootPath.toRealPath()
            } catch (exception: IOException) {
                if (!Files.notExists(rootPath)) recordUnreadable(rootPath)
                continue
            } catch (exception: SecurityException) {
                recordUnreadable(rootPath)
                continue
            }
            if (isExcludedDirectory(realRoot)) continue
            try {
                // Not following symbolic links prevents both traversal loops and root escapes.
                Files.walkFileTree(realRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        checkCancellation()
                        if (isExcludedDirectory(dir) || !visitedDirectories.add(dir)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (!Files.isReadable(dir) || !Files.isExecutable(dir)) {
                            recordUnreadable(dir)
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        checkCancellation()
                        val name = file.fileName.toString()
                        if (attrs.isRegularFile && name.endsWith(".pdf", ignoreCase = true)) {
                            val path = file.toString()
                            documents[path] = PdfDocument(
                                path = path,
                                title = name,
                                modifiedAt = attrs.lastModifiedTime().toMillis(),
                                sizeBytes = attrs.size(),
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        checkCancellation()
                        if (file == realRoot || Files.isDirectory(file)) recordUnreadable(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        checkCancellation()
                        if (exc != null) recordUnreadable(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (exception: IOException) {
                recordUnreadable(realRoot)
            } catch (exception: SecurityException) {
                recordUnreadable(realRoot)
            }
        }
        checkCancellation()
        val sortedDocuments = documents.values.sortedWith(
            compareByDescending<PdfDocument> { it.modifiedAt }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.title }
                .thenBy { it.path },
        )
        checkCancellation()
        return ScanResult(sortedDocuments, unreadableDirectories.size)
    }

    private fun isExcludedDirectory(path: Path): Boolean {
        if (path.any { it.toString().startsWith(".") }) return true
        return (1 until path.nameCount).any { index ->
            path.getName(index - 1).toString().equals("Android", ignoreCase = true) &&
                (
                    path.getName(index).toString().equals("data", ignoreCase = true) ||
                        path.getName(index).toString().equals("obb", ignoreCase = true)
                    )
        }
    }
}
