package com.zip2apk.builder.model

import java.io.File

enum class BuildPhase {
    IDLE,
    IMPORTING,
    ANALYZING,
    FIXING,
    TOOLCHAIN_SETUP,
    READY,
    BUILDING,
    SUCCESS,
    ERROR
}

data class ProjectInfo(
    val root: File,
    val name: String,
    val gradleVersion: String?,
    val agpVersion: String?,
    val compileSdk: Int?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val ndkVersion: String?,
    val cmakeVersion: String?,
    val buildToolsVersion: String?,
    val packageName: String?,
    val applicationId: String?,
    val modules: List<String>,
    val hasKotlin: Boolean,
    val hasJava: Boolean,
    val hasCpp: Boolean,
    val hasCmake: Boolean,
    val hasNdkBuild: Boolean
) {
    val languageSummary: String
        get() = buildList {
            if (hasKotlin) add("Kotlin")
            if (hasJava) add("Java")
            if (hasCpp) add("C/C++")
        }.ifEmpty { listOf("Unknown") }.joinToString(" + ")

    // Native source files alone do not imply Gradle is configured to build them.
    // Only activate the native bridge when an Android native build system is actually present.
    val usesNativeBuild: Boolean get() = hasCmake || hasNdkBuild
}


enum class NativeBuildSystem {
    CMAKE,
    NDK_BUILD
}

data class NativeModuleSpec(
    val modulePath: String,
    val moduleDir: File,
    val buildSystem: NativeBuildSystem,
    val scriptFile: File,
    val abiFilters: Set<String> = emptySet(),
    val cmakeArguments: List<String> = emptyList(),
    val cFlags: List<String> = emptyList(),
    val cppFlags: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val stl: String = "c++_shared"
)

data class NativeDependencyResolution(
    val successful: Boolean,
    /** Resolved AARs keyed by the consuming Gradle module path (for example `:app`). */
    val moduleAars: Map<String, List<File>>,
    val warnings: List<String> = emptyList(),
    val error: String? = null
) {
    fun aarFilesFor(modulePath: String): List<File> = moduleAars[modulePath].orEmpty()
    val allAars: List<File> get() = moduleAars.values.flatten().distinctBy { it.absolutePath }
}

data class PrefabIntegrationResult(
    val packageNames: List<String>,
    val cmakeRoot: File?,
    /**
     * Concrete directories containing generated package config files such as
     * `oboeConfig.cmake`. These are intentionally passed directly to
     * `CMAKE_PREFIX_PATH` because Zip2APK runs Termux-hosted CMake without the
     * desktop NDK toolchain file that normally teaches CMake Prefab's
     * `lib/<target-triple>/cmake/<package>` layout.
     */
    val cmakePackageDirs: List<File> = emptyList(),
    /** Exact generated config directory for each Prefab package name. */
    val cmakePackageDirsByName: Map<String, File> = emptyMap(),
    val packagedSharedLibraries: List<File>,
    val warnings: List<String> = emptyList()
)

data class ToolchainSummary(
    val installed: Boolean,
    val root: File,
    val prefix: File?,
    val home: File?,
    val javaExecutable: File?,
    val javaHome: File?,
    val sdkRoot: File?,
    val aapt2: File?,
    val gradleExecutable: File?,
    val cmakeExecutable: File?,
    val ninjaExecutable: File?,
    val clangExecutable: File?,
    val clangxxExecutable: File?,
    val ndkSysrootInstalled: Boolean,
    val ndkMajor: Int?,
    val problems: List<String>
) {
    val nativeReady: Boolean
        get() = cmakeExecutable?.canExecute() == true &&
            ninjaExecutable?.canExecute() == true &&
            clangExecutable?.canExecute() == true &&
            clangxxExecutable?.canExecute() == true &&
            ndkSysrootInstalled
}

data class ComponentVersion(
    val name: String,
    val version: String
)

data class ApkArtifact(
    val file: File,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sizeBytes: Long,
    val builtAtMillis: Long,
    val sha256: String
)



data class BuildSigningConfig(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val certificateSha256: String
)

data class SigningIdentityStatus(
    val available: Boolean,
    val privateStorePath: String?,
    val certificateSha256: String?,
    val installedCertificateSha256: String?,
    val matchesInstalledApp: Boolean,
    val message: String
)

enum class ApkInstallCompatibilityKind {
    NEW_INSTALL,
    UPDATE_OK,
    SIGNATURE_MISMATCH,
    DOWNGRADE,
    INVALID_APK,
    UNKNOWN
}

data class ApkInstallAssessment(
    val kind: ApkInstallCompatibilityKind,
    val apk: File,
    val packageName: String?,
    val targetVersionCode: Long?,
    val installedVersionCode: Long?,
    val targetCertificateSha256: String?,
    val installedCertificateSha256: String?,
    val message: String
) {
    val canAttemptInstall: Boolean
        get() = kind in setOf(ApkInstallCompatibilityKind.NEW_INSTALL, ApkInstallCompatibilityKind.UPDATE_OK, ApkInstallCompatibilityKind.UNKNOWN)
}


data class FixAction(
    val title: String,
    val detail: String,
    val changedFiles: List<File> = emptyList()
)

data class BuildExecution(
    val exitCode: Int,
    val output: String,
    val command: List<String>
) {
    val successful: Boolean get() = exitCode == 0
}

data class NativeBuildResult(
    val successful: Boolean,
    val builtLibraries: List<File> = emptyList(),
    val error: String? = null
)

data class BuildResult(
    val successful: Boolean,
    val apk: File?,
    val fixes: List<FixAction>,
    val attempts: Int,
    val error: String? = null
)

data class BuilderUiState(
    val phase: BuildPhase = BuildPhase.IDLE,
    val selectedSourceName: String? = null,
    val project: ProjectInfo? = null,
    val toolchain: ToolchainSummary? = null,
    val fixes: List<FixAction> = emptyList(),
    val logs: List<String> = emptyList(),
    val apk: File? = null,
    val apkHistory: List<ApkArtifact> = emptyList(),
    val componentVersions: List<ComponentVersion> = emptyList(),
    val signingIdentity: SigningIdentityStatus? = null,
    val error: String? = null,
    val busyMessage: String? = null
)
