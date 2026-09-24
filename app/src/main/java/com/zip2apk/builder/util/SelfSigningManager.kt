package com.zip2apk.builder.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zip2apk.builder.BuildConfig
import com.zip2apk.builder.model.BuildSigningConfig
import com.zip2apk.builder.model.ProjectInfo
import com.zip2apk.builder.model.SigningIdentityStatus
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * Owns the private signing identity used only for Zip2APK self-updates.
 *
 * The first desktop build generates a per-install PKCS#12 key and embeds a
 * copy in that APK. On first launch the key is moved into app-private storage.
 * Subsequent self-builds pass this private copy back into Gradle, keeping the
 * signing certificate stable across versions.
 */
class SelfSigningManager(private val context: Context) {
    private val signingDir = File(context.filesDir, "signing")
    private val privateStore = File(signingDir, "zip2apk-update.p12")

    fun ensureInitialized(): SigningIdentityStatus {
        signingDir.mkdirs()
        if (!privateStore.isFile) {
            context.assets.open(BuildConfig.ZIP2APK_UPDATE_KEY_ASSET).use { input ->
                privateStore.outputStream().use(input::copyTo)
            }
            // The parent directory is already app-private; tighten the file mode as well.
            privateStore.setReadable(false, false)
            privateStore.setWritable(false, false)
            privateStore.setReadable(true, true)
            privateStore.setWritable(true, true)
        }
        return inspect()
    }

    fun inspect(): SigningIdentityStatus = runCatching {
        if (!privateStore.isFile) {
            return SigningIdentityStatus(
                available = false,
                privateStorePath = null,
                certificateSha256 = null,
                installedCertificateSha256 = installedSignerDigests().firstOrNull(),
                matchesInstalledApp = false,
                message = "Persistent self-update signing key has not been initialized."
            )
        }

        val keyFingerprint = keyStoreFingerprint(privateStore)
        val installedFingerprints = installedSignerDigests()
        val matches = keyFingerprint != null && installedFingerprints.any { it.equals(keyFingerprint, ignoreCase = true) }
        SigningIdentityStatus(
            available = keyFingerprint != null,
            privateStorePath = privateStore.absolutePath,
            certificateSha256 = keyFingerprint,
            installedCertificateSha256 = installedFingerprints.firstOrNull(),
            matchesInstalledApp = matches,
            message = when {
                keyFingerprint == null -> "The stored self-update key could not be read."
                matches -> "Persistent update signing identity is established."
                installedFingerprints.isEmpty() -> "The installed app certificate could not be inspected."
                else -> "The stored update key does not match this installed APK. One final reinstall is required before in-place self-updates can work."
            }
        )
    }.getOrElse { error ->
        SigningIdentityStatus(
            available = false,
            privateStorePath = privateStore.takeIf(File::isFile)?.absolutePath,
            certificateSha256 = null,
            installedCertificateSha256 = runCatching { installedSignerDigests().firstOrNull() }.getOrNull(),
            matchesInstalledApp = false,
            message = error.message ?: "Unable to inspect the self-update signing identity."
        )
    }

    fun signingFor(project: ProjectInfo, onLine: (String) -> Unit): BuildSigningConfig? {
        if (!isZip2ApkProject(project)) return null
        validateSelfBuildSources(project)
        val status = ensureInitialized()
        val fingerprint = requireNotNull(status.certificateSha256) {
            "Zip2APK self-build detected, but the persistent update signing key is unavailable."
        }
        if (!status.matchesInstalledApp) {
            onLine("WARNING: Zip2APK self-build signing identity differs from the currently installed APK.")
            onLine("This build can be produced, but Android will require one final uninstall/reinstall before future in-place updates work.")
        } else {
            onLine("Zip2APK self-build detected; reusing persistent update signing identity ${shortFingerprint(fingerprint)}.")
        }
        return BuildSigningConfig(
            storeFile = privateStore,
            storePassword = BuildConfig.ZIP2APK_UPDATE_STORE_PASSWORD,
            keyAlias = BuildConfig.ZIP2APK_UPDATE_KEY_ALIAS,
            keyPassword = BuildConfig.ZIP2APK_UPDATE_KEY_PASSWORD,
            certificateSha256 = fingerprint
        )
    }

    private fun validateSelfBuildSources(project: ProjectInfo) {
        val required = listOf(
            "app/src/main/java/com/zip2apk/builder/build/BuildCoordinator.kt",
            "app/src/main/java/com/zip2apk/builder/build/GradleBuildRunner.kt",
            "app/src/main/java/com/zip2apk/builder/build/NativePrebuilder.kt",
            "app/src/main/java/com/zip2apk/builder/build/NativeBuildPlanner.kt",
            "app/src/main/java/com/zip2apk/builder/build/NativeBuildBackend.kt",
            "app/src/main/java/com/zip2apk/builder/build/CMakeNativeBackend.kt",
            "app/src/main/java/com/zip2apk/builder/build/NativeDependencyResolver.kt",
            "app/src/main/java/com/zip2apk/builder/build/PrefabIntegrator.kt",
            "app/src/main/java/com/zip2apk/builder/build/ProjectAnalyzer.kt",
            "app/src/main/java/com/zip2apk/builder/build/TermuxEnvironment.kt",
            "app/src/main/java/com/zip2apk/builder/build/ToolchainManager.kt",
            "app/src/main/java/com/zip2apk/builder/build/ZipProjectImporter.kt"
        )
        val missing = required.filterNot { File(project.root, it).isFile }
        require(missing.isEmpty()) {
            "Incomplete Zip2APK source archive. Missing: ${missing.joinToString()}. " +
                "Use a full source ZIP rather than a UI-only patch archive."
        }
    }

    fun isZip2ApkProject(project: ProjectInfo): Boolean {
        if (project.applicationId != context.packageName) return false
        val settingsText = listOf(
            File(project.root, "settings.gradle.kts"),
            File(project.root, "settings.gradle")
        ).firstOrNull(File::isFile)?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
        return settingsText.contains("Zip2APK", ignoreCase = true) ||
            File(project.root, "app/src/main/java/com/zip2apk/builder").isDirectory
    }

    private fun keyStoreFingerprint(file: File): String? {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().buffered().use { input ->
            store.load(input, BuildConfig.ZIP2APK_UPDATE_STORE_PASSWORD.toCharArray())
        }
        val certificate = store.getCertificate(BuildConfig.ZIP2APK_UPDATE_KEY_ALIAS) ?: return null
        return sha256(certificate)
    }

    private fun installedSignerDigests(): List<String> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners.orEmpty().map { signature -> sha256(signature.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { signature -> sha256(signature.toByteArray()) }
        }
    }

    private fun sha256(certificate: Certificate): String = sha256(certificate.encoded)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { "%02X".format(it) }

    private fun shortFingerprint(value: String): String = value.split(':').take(6).joinToString(":") + "…"
}
