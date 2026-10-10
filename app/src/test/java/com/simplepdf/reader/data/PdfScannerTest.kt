package com.simplepdf.reader.data

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PdfScannerTest {
    private lateinit var workspace: Path

    @Before
    fun createWorkspace() {
        workspace = File("build/pdf-scanner-tests/${UUID.randomUUID()}").toPath().toAbsolutePath()
        Files.createDirectories(workspace)
    }

    @After
    fun removeWorkspace() {
        Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun scansRecursivelyAndSortsNewestFirstWithStableTies() {
        val old = pdf("root/old.pdf", 1000)
        val lower = pdf("root/sub/a.pdf", 2000)
        val upper = pdf("root/A.PDF", 2000)
        val duplicateName = pdf("root/sub2/a.pdf", 2000)
        val newest = pdf("root/new.PdF", 3000)
        pdf("root/not-a-pdf.txt", 4000)
        Files.createDirectories(workspace.resolve("root/directory.pdf"))

        val result = PdfScanner.scan(listOf(workspace.resolve("root").toFile()))

        assertEquals(
            listOf(newest, upper, lower, duplicateName, old).map { it.toString() },
            result.documents.map { it.path },
        )
        assertEquals(listOf("new.PdF", "A.PDF", "a.pdf", "a.pdf", "old.pdf"), result.documents.map { it.title })
        assertEquals(listOf(3000L, 2000L, 2000L, 2000L, 1000L), result.documents.map { it.modifiedAt })
        assertEquals(List(5) { 3L }, result.documents.map { it.sizeBytes })
        assertEquals(0, result.unreadableDirectories)
    }

    @Test
    fun overlappingAndRepeatedRootsAreDeduplicatedRegardlessOfOrder() {
        pdf("root/top.pdf")
        pdf("root/child/nested.pdf")
        val root = workspace.resolve("root").toFile()
        val child = workspace.resolve("root/child").toFile()
        val forward = PdfScanner.scan(listOf(root, child, root))
        val reverse = PdfScanner.scan(listOf(child, root, child))

        assertEquals(2, forward.documents.size)
        assertEquals(forward, reverse)
        assertEquals(PdfScanner.scan(listOf(root)), forward)
    }

    @Test
    fun hiddenAndRestrictedAndroidDirectoriesAreSkippedEvenAsExplicitRoots() {
        pdf("root/.hidden/secret.pdf")
        pdf("root/visible/.nested/secret.pdf")
        pdf("root/Android/data/package/files/private.pdf")
        pdf("root/Android/obb/package/asset.pdf")
        pdf("root/android/DATA/other/private.pdf")
        val visible = pdf("root/Android/media/package/shared.pdf")
        val normal = pdf("root/data/normal.pdf")
        val roots = listOf(
            "root",
            "root/.hidden",
            "root/Android/data",
            "root/Android/obb/package",
        ).map { workspace.resolve(it).toFile() }

        assertEquals(
            setOf(visible.toString(), normal.toString()),
            PdfScanner.scan(roots).documents.map { it.path }.toSet(),
        )
    }

    @Test
    fun symbolicLinksDoNotEscapeOrLoopAndAliasedRootsAreDeduplicated() {
        val inside = pdf("root/inside.pdf")
        val outside = pdf("outside/private.pdf")
        val root = workspace.resolve("root")
        Files.createSymbolicLink(root.resolve("escape"), outside.parent)
        Files.createSymbolicLink(root.resolve("loop"), root)
        Files.createSymbolicLink(root.resolve("linked.pdf"), outside)
        Files.createSymbolicLink(workspace.resolve("root-alias"), root)

        val result = PdfScanner.scan(listOf(root.toFile(), workspace.resolve("root-alias").toFile()))

        assertEquals(listOf(inside.toString()), result.documents.map { it.path })
        assertEquals(0, result.unreadableDirectories)
    }

    @Test
    fun emptyAndMissingRootsAreHarmless() {
        assertEquals(ScanResult(emptyList(), 0), PdfScanner.scan(emptyList()))
        assertEquals(ScanResult(emptyList(), 0), PdfScanner.scan(listOf(workspace.resolve("missing").toFile())))
    }

    @Test
    fun cancellationIsReportedBeforeAndDuringTraversal() {
        assertThrows(CancellationException::class.java) { PdfScanner.scan(emptyList()) { true } }
        repeat(20) { pdf("root/nested/$it.pdf") }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PdfScanner.scan(listOf(workspace.resolve("root").toFile())) { ++checks > 6 }
        }
    }

    @Test
    fun deniedDirectoryIsCountedOnceInsteadOfReportedAsAnEmptySuccessfulScan() {
        assumeTrue(workspace.fileSystem.supportedFileAttributeViews().contains("posix"))
        val blocked = pdf("root/blocked/private.pdf").parent
        val permissions = Files.getPosixFilePermissions(blocked)
        try {
            Files.setPosixFilePermissions(blocked, emptySet<PosixFilePermission>())
            assumeFalse("The current user can bypass directory permissions", Files.isReadable(blocked))
            val result = PdfScanner.scan(
                listOf(workspace.resolve("root").toFile(), blocked.toFile(), blocked.toFile()),
            )
            assertEquals(1, result.unreadableDirectories)
            assertEquals(emptyList<PdfDocument>(), result.documents)
        } finally {
            Files.setPosixFilePermissions(blocked, permissions)
        }
    }

    @Test
    fun scannerUsesMetadataWithoutReadingPdfContents() {
        assumeTrue(workspace.fileSystem.supportedFileAttributeViews().contains("posix"))
        val file = pdf("root/unreadable-content.pdf")
        val permissions = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, emptySet<PosixFilePermission>())
            val result = PdfScanner.scan(listOf(workspace.resolve("root").toFile()))
            assertEquals(listOf(file.toString()), result.documents.map { it.path })
            assertEquals(3L, result.documents.single().sizeBytes)
            assertFalse(result.documents.isEmpty())
        } finally {
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    private fun pdf(relativePath: String, modifiedAt: Long = 1000): Path {
        val file = workspace.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt))
        return file.toRealPath()
    }
}
