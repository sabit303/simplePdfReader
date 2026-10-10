package com.simplepdf.reader.data

import android.content.Context
import android.os.Environment
import java.io.File

@Suppress("DEPRECATION")
fun sharedStorageRoots(context: Context): List<File> {
    val roots = mutableListOf(Environment.getExternalStorageDirectory().canonicalFile)
    context.getExternalFilesDirs(null).filterNotNull().forEach { externalFiles ->
        val files = externalFiles.canonicalFile
        val application = files.parentFile
        val data = application?.parentFile
        val android = data?.parentFile
        if (
            files.name == "files" &&
            application?.name == context.packageName &&
            data?.name == "data" &&
            android?.name == "Android"
        ) {
            android.parentFile?.let { roots.add(it) }
        }
    }
    return roots.distinctBy { it.path }
}
