package com.zip2apk.builder.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zip2apk.builder.model.ApkInstallAssessment
import com.zip2apk.builder.model.ApkInstallCompatibilityKind
import java.io.File
import java.security.MessageDigest

class ApkCompatibilityChecker(private val context: Context) {

    fun assess(apk: File): ApkInstallAssessment {
        val target = archiveInfo(apk) ?: return ApkInstallAssessment(
            kind = ApkInstallCompatibilityKind.INVALID_APK,
            apk = apk,
            packageName = null,
            targetVersionCode = null,
            installedVersionCode = null,
            targetCertificateSha256 = null,
            installedCertificateSha256 = null,
            message = "Android could not read package metadata from this APK."
        )

        val packageName = target.packageName
        val targetVersion = versionCode(target)
        val targetSigners = signerDigests(target)
        val installed = installedInfo(packageName)
            ?: return ApkInstallAssessment(
                kind = ApkInstallCompatibilityKind.NEW_INSTALL,
                apk = apk,
                packageName = packageName,
                targetVersionCode = targetVersion,
                installedVersionCode = null,
                targetCertificateSha256 = targetSigners.firstOrNull(),
                installedCertificateSha256 = null,
                message = "This package is not currently installed."
            )

        val installedVersion = versionCode(installed)
        val installedSigners = signerDigests(installed)
        if (targetSigners.isNotEmpty() && installedSigners.isNotEmpty()) {
            val sameSignerSet = targetSigners.map { it.uppercase() }.toSet() == installedSigners.map { it.uppercase() }.toSet()
            if (!sameSignerSet) {
                return ApkInstallAssessment(
                    kind = ApkInstallCompatibilityKind.SIGNATURE_MISMATCH,
                    apk = apk,
                    packageName = packageName,
                    targetVersionCode = targetVersion,
                    installedVersionCode = installedVersion,
                    targetCertificateSha256 = targetSigners.firstOrNull(),
                    installedCertificateSha256 = installedSigners.firstOrNull(),
                    message = if (packageName == context.packageName) {
                        "This Zip2APK APK uses a different signing identity from the installed copy. Android cannot install it as an update. Save the APK outside the app, uninstall the current Zip2APK once, then install the saved APK. Future self-updates will reuse the new persistent identity."
                    } else {
                        "Android cannot update $packageName because the built APK and the installed app are signed by different certificates."
                    }
                )
            }
        }

        if (targetVersion < installedVersion) {
            return ApkInstallAssessment(
                kind = ApkInstallCompatibilityKind.DOWNGRADE,
                apk = apk,
                packageName = packageName,
                targetVersionCode = targetVersion,
                installedVersionCode = installedVersion,
                targetCertificateSha256 = targetSigners.firstOrNull(),
                installedCertificateSha256 = installedSigners.firstOrNull(),
                message = "The built APK has version code $targetVersion, but the installed app is version code $installedVersion. Android normally blocks downgrades."
            )
        }

        if (targetSigners.isEmpty() || installedSigners.isEmpty()) {
            return ApkInstallAssessment(
                kind = ApkInstallCompatibilityKind.UNKNOWN,
                apk = apk,
                packageName = packageName,
                targetVersionCode = targetVersion,
                installedVersionCode = installedVersion,
                targetCertificateSha256 = targetSigners.firstOrNull(),
                installedCertificateSha256 = installedSigners.firstOrNull(),
                message = "The package can be opened in Android's installer, but Zip2APK could not verify its signing certificate first."
            )
        }

        return ApkInstallAssessment(
            kind = ApkInstallCompatibilityKind.UPDATE_OK,
            apk = apk,
            packageName = packageName,
            targetVersionCode = targetVersion,
            installedVersionCode = installedVersion,
            targetCertificateSha256 = targetSigners.firstOrNull(),
            installedCertificateSha256 = installedSigners.firstOrNull(),
            message = "Signing certificate is compatible with the installed app."
        )
    }

    private fun archiveInfo(apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    private fun installedInfo(packageName: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun signerDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { "%02X".format(it) }
}
