package com.zip2apk.builder.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zip2apk.builder.model.ApkArtifact
import java.io.File
import java.security.MessageDigest

class ApkHistoryStore(private val context: Context) {
    private val root = File(context.filesDir, "apk-history").apply { mkdirs() }

    fun archive(source: File, projectName: String): File {
        root.mkdirs()
        val safeProject = projectName
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "app" }
        val destination = File(root, "$safeProject-${System.currentTimeMillis()}.apk")
        source.copyTo(destination, overwrite = true)
        destination.setLastModified(System.currentTimeMillis())
        prune()
        return destination
    }

    fun load(): List<ApkArtifact> = root
        .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .take(MAX_HISTORY)
        .map(::inspect)

    fun delete(file: File) {
        if (file.canonicalFile.parentFile == root.canonicalFile) file.delete()
    }

    private fun inspect(file: File): ApkArtifact {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        }.getOrNull()
        val appInfo = packageInfo?.applicationInfo
        val versionCode = packageInfo?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else legacyVersionCode(info)
        }
        return ApkArtifact(
            file = file,
            packageName = packageInfo?.packageName,
            versionName = packageInfo?.versionName,
            versionCode = versionCode,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo?.minSdkVersion else null,
            targetSdk = appInfo?.targetSdkVersion,
            sizeBytes = file.length(),
            builtAtMillis = file.lastModified(),
            sha256 = sha256(file)
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Long = info.versionCode.toLong()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prune() {
        root.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(MAX_HISTORY)
            .forEach(File::delete)
    }

    companion object {
        private const val MAX_HISTORY = 20
    }
}
