package dev.soranerai.vpnhidenext.db

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Small persistence primitive for the app-owned atomically replaced JSON file. */
internal class AtomicJsonFile(private val file: File) {
    private val atomicFile = AtomicFile(file)

    fun exists(): Boolean = file.exists()

    fun readText(): String =
        atomicFile.openRead().use { input ->
            input.reader().readText()
        }

    fun writeText(value: String) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(value.toByteArray())
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }
}
