package com.zip2apk.builder

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zip2apk.builder.build.AutoFixEngine
import com.zip2apk.builder.build.BuildCoordinator
import com.zip2apk.builder.build.ProjectAnalyzer
import com.zip2apk.builder.build.ToolchainManager
import com.zip2apk.builder.build.ZipProjectImporter
import com.zip2apk.builder.model.BuildPhase
import com.zip2apk.builder.model.BuilderUiState
import com.zip2apk.builder.util.ApkHistoryStore
import com.zip2apk.builder.util.AppPreferences
import com.zip2apk.builder.util.SelfSigningManager
import com.zip2apk.builder.util.SelfUpdateSourceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BuilderViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = ZipProjectImporter(application)
    private val analyzer = ProjectAnalyzer()
    private val fixer = AutoFixEngine()
    private val toolchains = ToolchainManager(application)
    private val coordinator = BuildCoordinator(application)
    private val preferences = AppPreferences(application)
    private val apkHistoryStore = ApkHistoryStore(application)
    private val selfSigning = SelfSigningManager(application)
    private val selfUpdateFinder = SelfUpdateSourceFinder(application)
    private var lastBuildNotificationUpdateAt = 0L

    private val _state = BuildSessionRuntime.state
    val state: StateFlow<BuilderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = toolchains.inspect()
            val history = apkHistoryStore.load()
            val signing = selfSigning.ensureInitialized()
            _state.update { current ->
                current.copy(
                    toolchain = summary,
                    apkHistory = if (current.apkHistory.isEmpty()) history else current.apkHistory,
                    signingIdentity = signing
                )
            }
        }
    }

    fun refreshComponentVersions() {
        viewModelScope.launch(Dispatchers.IO) {
            val versions = toolchains.inspectComponentVersions()
            _state.update { it.copy(componentVersions = versions, toolchain = toolchains.inspect()) }
        }
    }

    fun refreshApkHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(apkHistory = apkHistoryStore.load()) }
        }
    }

    fun refreshSigningIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(signingIdentity = selfSigning.ensureInitialized()) }
        }
    }

    fun deleteArchivedApk(file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            apkHistoryStore.delete(file)
            _state.update { it.copy(apkHistory = apkHistoryStore.load()) }
        }
    }

    fun setupToolchain() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = BuildPhase.TOOLCHAIN_SETUP,
                    busyMessage = "Setting up local ARM64 toolchain",
                    error = null,
                    logs = (it.logs + "Starting automatic toolchain setup…").takeLast(MAX_LOG_LINES)
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    toolchains.setupAutomatically(::appendLog)
                    val project = _state.value.project
                    if (project != null) toolchains.ensureForProject(project, ::appendLog)
                    val summary = toolchains.inspect()
                    require(summary.installed) { summary.problems.joinToString("\n") }
                    val extraFixes = if (project != null && preferences.autoFixEnabled) fixer.applyPreBuildFixes(project, summary) else emptyList()
                    summary to extraFixes
                }
            }.onSuccess { (summary, fixes) ->
                _state.update {
                    it.copy(
                        phase = if (it.project != null) BuildPhase.READY else BuildPhase.IDLE,
                        toolchain = summary,
                        fixes = (it.fixes + fixes).distinctBy { f -> f.title to f.detail },
                        busyMessage = null,
                        error = null
                    )
                }
                appendLog("Toolchain setup completed.")
                if (_state.value.project != null && preferences.autoBuildAfterPreparation) {
                    appendLog("Automatic build after preparation is enabled; starting build…")
                    buildApk()
                }
            }.onFailure(::failWithoutChangingReadyProject)
        }
    }

    fun importSourceZip(uri: Uri, forceBuildAfterPreparation: Boolean = false) {
        if (BuildSessionRuntime.preparationJob?.isActive == true || BuildSessionRuntime.buildJob?.isActive == true) return
        val autoBuildRequested = forceBuildAfterPreparation || preferences.autoBuildAfterPreparation
        val app = getApplication<Application>()
        val sourceName = displayName(uri)

        if (autoBuildRequested) {
            runCatching { BuildForegroundService.start(app, sourceName ?: "Android source ZIP") }
            BuildForegroundService.update(app, "Preparing source", sourceName ?: "Importing Android project")
            BuildSessionRuntime.cancelActive = {
                BuildSessionRuntime.preparationJob?.cancel()
                toolchains.cancel()
            }
        }

        BuildSessionRuntime.preparationJob = BuildSessionRuntime.scope.launch {
            _state.update {
                it.copy(
                    phase = BuildPhase.IMPORTING,
                    selectedSourceName = sourceName,
                    project = null,
                    fixes = emptyList(),
                    logs = listOf("Importing source ZIP…"),
                    apk = null,
                    error = null,
                    busyMessage = "Extracting project"
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    if (autoBuildRequested) BuildForegroundService.update(app, "Preparing source", "Extracting project")
                    val imported = importer.import(uri)
                    appendLog("Project extracted to ${imported.projectRoot.absolutePath}")
                    setPhase(BuildPhase.ANALYZING, "Inspecting Gradle project")
                    if (autoBuildRequested) BuildForegroundService.update(app, "Preparing source", "Inspecting Gradle project")
                    val info = analyzer.analyze(imported.projectRoot)
                    appendLog("Detected ${info.languageSummary}; modules: ${info.modules.ifEmpty { listOf("unknown") }.joinToString()}")
                    val toolchain = toolchains.inspect()
                    if (toolchain.installed) {
                        if (autoBuildRequested) BuildForegroundService.update(app, "Preparing source", "Checking project SDK requirements")
                        toolchains.ensureForProject(info, ::appendLog)
                    }
                    setPhase(BuildPhase.FIXING, "Applying safe automatic fixes")
                    if (autoBuildRequested) BuildForegroundService.update(app, "Preparing source", "Applying safe automatic fixes")
                    val refreshedToolchain = toolchains.inspect()
                    val fixes = if (preferences.autoFixEnabled) fixer.applyPreBuildFixes(info, refreshedToolchain) else emptyList()
                    fixes.forEach { appendLog("AUTO-FIX: ${it.title} — ${it.detail}") }
                    Triple(analyzer.analyze(imported.projectRoot), fixes, refreshedToolchain)
                }
            }.onSuccess { (project, fixes, toolchain) ->
                _state.update {
                    it.copy(
                        phase = BuildPhase.READY,
                        project = project,
                        fixes = fixes,
                        toolchain = toolchain,
                        error = null,
                        busyMessage = null
                    )
                }
                appendLog(if (toolchain.installed) "Ready to build." else "Project is ready; run automatic toolchain setup before building.")
                BuildSessionRuntime.preparationJob = null
                BuildSessionRuntime.cancelActive = null
                if (autoBuildRequested && toolchain.installed) {
                    appendLog(if (forceBuildAfterPreparation) "Fast self-update source prepared; starting build…" else "Automatic build after preparation is enabled; starting build…")
                    buildApk()
                } else {
                    if (autoBuildRequested) BuildForegroundService.stop(app)
                    if (forceBuildAfterPreparation && !toolchain.installed) {
                        val message = "Fast self-update cannot start because the local build toolchain is not ready."
                        _state.update { it.copy(error = message) }
                        appendLog("ERROR: $message")
                    }
                }
            }.onFailure { failure ->
                BuildSessionRuntime.preparationJob = null
                BuildSessionRuntime.cancelActive = null
                if (failure is CancellationException) {
                    if (autoBuildRequested) BuildForegroundService.stop(app)
                } else {
                    fail(failure)
                    if (autoBuildRequested) BuildForegroundService.failed(app, conciseFailure(failure.message ?: failure::class.java.simpleName))
                }
            }
        }
    }

    fun fastSelfUpdateFromDownloads() {
        if (_state.value.busyMessage != null || BuildSessionRuntime.preparationJob?.isActive == true || BuildSessionRuntime.buildJob?.isActive == true) return
        BuildSessionRuntime.scope.launch {
            val previousPhase = if (_state.value.project != null) BuildPhase.READY else BuildPhase.IDLE
            _state.update {
                it.copy(
                    phase = BuildPhase.IMPORTING,
                    busyMessage = "Looking for a newer Zip2APK source",
                    error = null,
                    logs = (it.logs + "Fast self-update: scanning Downloads for a newer Zip2APK source ZIP…").takeLast(MAX_LOG_LINES)
                )
            }
            when (val result = withContext(Dispatchers.IO) { selfUpdateFinder.findNewestNewerSource() }) {
                is SelfUpdateSourceFinder.Result.Found -> {
                    val candidate = result.candidate
                    appendLog("Fast self-update: found ${candidate.displayName} (version ${candidate.versionName ?: candidate.versionCode}, versionCode ${candidate.versionCode}; installed ${result.installedVersionCode}).")
                    importSourceZip(candidate.uri, forceBuildAfterPreparation = true)
                }
                is SelfUpdateSourceFinder.Result.NoNewerSource -> {
                    val newest = result.newestSourceVersionCode?.toString() ?: "none"
                    val message = "No newer Zip2APK source ZIP was found in Downloads. Installed versionCode ${result.installedVersionCode}; newest valid source versionCode $newest. Same or older versions are not built."
                    _state.update { it.copy(phase = previousPhase, busyMessage = null, error = message) }
                    appendLog("FAST SELF-UPDATE: $message")
                }
                is SelfUpdateSourceFinder.Result.Unavailable -> {
                    _state.update { it.copy(phase = previousPhase, busyMessage = null, error = result.message) }
                    appendLog("FAST SELF-UPDATE ERROR: ${result.message}")
                }
            }
        }
    }

    fun importToolchainZip(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = BuildPhase.TOOLCHAIN_SETUP,
                    busyMessage = "Importing local toolchain bundle",
                    error = null,
                    logs = (it.logs + "Importing toolchain ZIP…").takeLast(MAX_LOG_LINES)
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    toolchains.installFromUri(uri)
                    val project = _state.value.project
                    if (project != null) toolchains.ensureForProject(project, ::appendLog)
                    val toolchain = toolchains.inspect()
                    require(toolchain.installed) { toolchain.problems.joinToString("\n") }
                    val extraFixes = if (project != null && preferences.autoFixEnabled) fixer.applyPreBuildFixes(project, toolchain) else emptyList()
                    toolchain to extraFixes
                }
            }.onSuccess { (toolchain, newFixes) ->
                _state.update {
                    it.copy(
                        phase = if (it.project != null) BuildPhase.READY else BuildPhase.IDLE,
                        toolchain = toolchain,
                        fixes = (it.fixes + newFixes).distinctBy { fix -> fix.title to fix.detail },
                        busyMessage = null,
                        error = null
                    )
                }
                appendLog("Toolchain imported and validated.")
                if (_state.value.project != null && preferences.autoBuildAfterPreparation) {
                    appendLog("Automatic build after preparation is enabled; starting build…")
                    buildApk()
                }
            }.onFailure(::failWithoutChangingReadyProject)
        }
    }

    fun buildApk() {
        val project = _state.value.project ?: return
        if (BuildSessionRuntime.buildJob?.isActive == true) return
        val app = getApplication<Application>()
        BuildSessionRuntime.buildJob = BuildSessionRuntime.scope.launch {
            _state.update { it.copy(phase = BuildPhase.BUILDING, apk = null, error = null, busyMessage = "Preparing build") }
            BuildSessionRuntime.cancelActive = { cancelBuildFromTaskRemoval() }
            runCatching { BuildForegroundService.start(app, _state.value.selectedSourceName ?: project.name) }
            BuildForegroundService.update(app, "Preparing build", project.name)
            try {
                val result = withContext(Dispatchers.IO) {
                    var toolchain = toolchains.inspect()
                    require(toolchain.installed) {
                        "Local build toolchain is not ready:\n${toolchain.problems.joinToString("\n")}"
                    }
                    updateBuildNotification("Preparing build", "Checking Android SDK packages")
                    appendLog("Checking Android SDK packages for this project…")
                    toolchains.ensureForProject(project, ::appendLog)
                    toolchain = toolchains.inspect()
                    if (project.hasCmake) {
                        require(toolchain.nativeReady) {
                            "CMake project detected, but CMake/Ninja/Clang/ndk-sysroot is incomplete. Run toolchain setup again."
                        }
                    }
                    val preFixes = if (preferences.autoFixEnabled) fixer.applyPreBuildFixes(project, toolchain) else emptyList()
                    preFixes.forEach { appendLog("AUTO-FIX: ${it.title} — ${it.detail}") }
                    val refreshed = analyzer.analyze(project.root)
                    val signing = selfSigning.signingFor(refreshed, ::appendLog)
                    _state.update { it.copy(busyMessage = "Building APK", signingIdentity = selfSigning.inspect()) }
                    updateBuildNotification("Building APK", refreshed.name)
                    coordinator.build(
                        project = refreshed,
                        toolchain = toolchain,
                        existingFixes = (_state.value.fixes + preFixes).distinctBy { it.title to it.detail },
                        onLine = ::appendLog,
                        autoFixEnabled = preferences.autoFixEnabled,
                        signing = signing
                    )
                }

                if (result.successful && result.apk != null) {
                    val archived = withContext(Dispatchers.IO) { runCatching { apkHistoryStore.archive(result.apk, project.name) }.getOrElse { result.apk } }
                    val history = withContext(Dispatchers.IO) { apkHistoryStore.load() }
                    _state.update {
                        it.copy(
                            phase = BuildPhase.SUCCESS,
                            apk = archived,
                            apkHistory = history,
                            fixes = result.fixes,
                            busyMessage = null,
                            error = null,
                            toolchain = toolchains.inspect(),
                            signingIdentity = selfSigning.inspect()
                        )
                    }
                    appendLog("BUILD SUCCESSFUL — ${archived.name}")
                    BuildForegroundService.stop(app)
                } else {
                    val message = result.error ?: "Build failed."
                    _state.update { it.copy(phase = BuildPhase.ERROR, fixes = result.fixes, busyMessage = null, error = message) }
                    BuildForegroundService.failed(app, conciseFailure(message))
                }
            } catch (cancelled: CancellationException) {
                BuildForegroundService.stop(app)
                throw cancelled
            } catch (t: Throwable) {
                fail(t)
                BuildForegroundService.failed(app, conciseFailure(t.message ?: t::class.java.simpleName))
            } finally {
                BuildSessionRuntime.cancelActive = null
                BuildSessionRuntime.buildJob = null
            }
        }
    }

    fun cancelBuild() {
        BuildSessionRuntime.preparationJob?.cancel()
        BuildSessionRuntime.buildJob?.cancel()
        coordinator.cancel()
        toolchains.cancel()
        BuildForegroundService.stop(getApplication())
        BuildSessionRuntime.cancelActive = null
        _state.update { it.copy(phase = if (it.project != null) BuildPhase.READY else BuildPhase.IDLE, busyMessage = null, error = "Operation cancelled.") }
        appendLog("Operation cancelled.")
    }

    private fun cancelBuildFromTaskRemoval() {
        coordinator.cancel()
        toolchains.cancel()
        BuildSessionRuntime.preparationJob?.cancel()
        BuildSessionRuntime.buildJob?.cancel()
        BuildSessionRuntime.cancelActive = null
    }

    fun clearLog() = _state.update { it.copy(logs = emptyList()) }

    fun clearWorkspaces() {
        if (_state.value.busyMessage != null) return
        val root = getApplication<Application>().filesDir.resolve("workspaces")
        root.deleteRecursively()
        root.mkdirs()
        _state.update {
            it.copy(
                phase = BuildPhase.IDLE,
                selectedSourceName = null,
                project = null,
                fixes = emptyList(),
                apk = null,
                error = null
            )
        }
        appendLog("Imported project workspaces cleared.")
    }

    fun clearGradleCache() {
        if (_state.value.busyMessage != null) return
        val app = getApplication<Application>()
        app.filesDir.resolve("gradle-home").deleteRecursively()
        app.cacheDir.resolve("gradle-tmp").deleteRecursively()
        appendLog("Gradle cache cleared.")
    }

    private fun setPhase(phase: BuildPhase, message: String?) = _state.update { it.copy(phase = phase, busyMessage = message) }

    private fun appendLog(line: String) {
        _state.update { state -> state.copy(logs = (state.logs + line).takeLast(MAX_LOG_LINES)) }
        if (_state.value.phase == BuildPhase.BUILDING) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBuildNotificationUpdateAt >= NOTIFICATION_UPDATE_INTERVAL_MS || isMajorBuildLine(line)) {
                lastBuildNotificationUpdateAt = now
                updateBuildNotification(_state.value.busyMessage ?: "Building APK", line)
            }
        }
    }

    private fun updateBuildNotification(status: String, detail: String) {
        BuildForegroundService.update(getApplication(), status, detail.replace(Regex("\\s+"), " ").trim().take(240))
    }

    private fun isMajorBuildLine(line: String): Boolean =
        line.startsWith("===") || line.startsWith("Prebuilding native") || line.startsWith("Resolving native") ||
            line.startsWith("Generating CMake") || line.startsWith("> Task") || line.startsWith("BUILD SUCCESSFUL") ||
            line.startsWith("FAILURE:") || Regex("^\\[\\d+/\\d+]").containsMatchIn(line)

    private fun conciseFailure(message: String): String =
        message.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }?.take(500)
            ?: "The APK build failed. Open Zip2APK for the complete log."

    private fun fail(t: Throwable) {
        _state.update { it.copy(phase = BuildPhase.ERROR, busyMessage = null, error = t.message ?: t::class.java.simpleName) }
        appendLog("ERROR: ${t.message ?: t::class.java.simpleName}")
    }

    private fun failWithoutChangingReadyProject(t: Throwable) {
        _state.update {
            it.copy(
                phase = if (it.project != null) BuildPhase.READY else BuildPhase.IDLE,
                busyMessage = null,
                error = t.message ?: t::class.java.simpleName,
                toolchain = toolchains.inspect()
            )
        }
        appendLog("ERROR: ${t.message ?: t::class.java.simpleName}")
    }

    private fun displayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        private const val MAX_LOG_LINES = 3_000
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
    }
}
