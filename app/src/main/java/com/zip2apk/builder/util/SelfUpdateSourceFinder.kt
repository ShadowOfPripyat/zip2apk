package com.zip2apk.builder.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.zip2apk.builder.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class SelfUpdateSourceFinder(private val context: Context) {
    data class Candidate(
        val uri: Uri,
        val displayName: String,
        val versionCode: Long,
        val versionName: String?,
        val modifiedAtMillis: Long
    )

    sealed interface Result {
        data class Found(val candidate: Candidate, val installedVersionCode: Long) : Result
        data class NoNewerSource(val installedVersionCode: Long, val newestSourceVersionCode: Long?, val inspectedArchives: Int) : Result
        data class Unavailable(val message: String) : Result
    }

    fun findNewestNewerSource(): Result {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val archives = runCatching {
            downloads.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
                .orEmpty()
                .sortedWith(
                    compareByDescending<File> { it.name.contains("zip2apk", ignoreCase = true) }
                        .thenByDescending(File::lastModified)
                )
        }.getOrElse { return Result.Unavailable("Unable to read Downloads: ${it.message ?: it::class.java.simpleName}") }

        val installed = BuildConfig.VERSION_CODE.toLong()
        var inspected = 0
        var newest: Candidate? = null
        for (archive in archives.take(MAX_ARCHIVES_TO_INSPECT)) {
            val metadata = runCatching { archive.inputStream().use(::inspectZip) }.getOrNull() ?: continue
            inspected++
            if (!metadata.isZip2ApkSource || metadata.versionCode == null) continue
            val candidate = Candidate(Uri.fromFile(archive), archive.name, metadata.versionCode, metadata.versionName, archive.lastModified())
            val previous = newest
            if (previous == null || candidate.versionCode > previous.versionCode ||
                (candidate.versionCode == previous.versionCode && candidate.modifiedAtMillis > previous.modifiedAtMillis)
            ) newest = candidate
        }

        val candidate = newest
        return if (candidate != null && candidate.versionCode > installed) Result.Found(candidate, installed)
        else Result.NoNewerSource(installed, candidate?.versionCode, inspected)
    }

    private data class SourceMetadata(val versionCode: Long?, val versionName: String?, val isZip2ApkSource: Boolean)

    private fun inspectZip(input: InputStream): SourceMetadata {
        var versionCode: Long? = null
        var versionName: String? = null
        var hasSettings = false
        var hasMainSource = false
        var applicationIdMatches = false
        var namespaceMatches = false
        var entries = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (entries < MAX_ZIP_ENTRIES) {
                val entry = zip.nextEntry ?: break
                entries++
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.endsWith("settings.gradle.kts") || name.endsWith("settings.gradle")) hasSettings = true
                if (name.endsWith("app/src/main/java/com/zip2apk/builder/MainActivity.kt") ||
                    name.endsWith("app/src/main/kotlin/com/zip2apk/builder/MainActivity.kt")
                ) hasMainSource = true
                if (!entry.isDirectory && (name.endsWith("app/build.gradle.kts") || name.endsWith("app/build.gradle"))) {
                    val script = readEntryText(zip, MAX_BUILD_SCRIPT_BYTES)
                    versionCode = VERSION_CODE.find(script)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: versionCode
                    versionName = VERSION_NAME.find(script)?.groupValues?.getOrNull(1) ?: versionName
                    applicationIdMatches = APPLICATION_ID.containsMatchIn(script) || applicationIdMatches
                    namespaceMatches = NAMESPACE.containsMatchIn(script) || namespaceMatches
                }
                zip.closeEntry()
            }
        }
        return SourceMetadata(versionCode, versionName, hasSettings && hasMainSource && applicationIdMatches && namespaceMatches)
    }

    private fun readEntryText(zip: ZipInputStream, limit: Int): String {
        val buffer = ByteArray(8192)
        val output = StringBuilder()
        var total = 0
        while (total < limit) {
            val read = zip.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            output.append(String(buffer, 0, read, Charsets.UTF_8))
            total += read
        }
        return output.toString()
    }

    companion object {
        private const val MAX_ARCHIVES_TO_INSPECT = 200
        private const val MAX_ZIP_ENTRIES = 20_000
        private const val MAX_BUILD_SCRIPT_BYTES = 512 * 1024
        private val VERSION_CODE = Regex("""\bversionCode\s*(?:=|\s)\s*(\d+)""")
        private val VERSION_NAME = Regex("""\bversionName\s*(?:=|\s)\s*[\"']([^\"']+)[\"']""")
        private val APPLICATION_ID = Regex("""\bapplicationId\s*(?:=|\s)\s*[\"']com\.termux[\"']""")
        private val NAMESPACE = Regex("""\bnamespace\s*(?:=|\s)\s*[\"']com\.zip2apk\.builder[\"']""")
    }
}
