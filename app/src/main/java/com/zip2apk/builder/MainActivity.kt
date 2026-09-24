package com.zip2apk.builder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zip2apk.builder.model.ApkArtifact
import com.zip2apk.builder.model.ApkInstallAssessment
import com.zip2apk.builder.model.ApkInstallCompatibilityKind
import com.zip2apk.builder.model.BuildPhase
import com.zip2apk.builder.model.BuilderUiState
import com.zip2apk.builder.ui.Zip2ApkTheme
import com.zip2apk.builder.util.ApkActions
import com.zip2apk.builder.util.ApkCompatibilityChecker
import com.zip2apk.builder.util.AppPreferences
import com.zip2apk.builder.util.ThemeMode
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: BuilderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preferences = remember { AppPreferences(this) }
            var themeMode by remember { mutableStateOf(preferences.themeMode) }
            var materialYou by remember { mutableStateOf(preferences.materialYouEnabled) }
            var autoInstall by remember { mutableStateOf(preferences.autoInstallAfterBuild) }
            var autoBuildAfterPreparation by remember { mutableStateOf(preferences.autoBuildAfterPreparation) }
            var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOnDuringBuild) }
            var autoFixEnabled by remember { mutableStateOf(preferences.autoFixEnabled) }
            var rootScreen by rememberSaveable {
                mutableStateOf(if (preferences.onboardingComplete) SCREEN_MAIN else SCREEN_ONBOARDING)
            }
            var settingsPage by rememberSaveable { mutableStateOf(SETTINGS_ROOT) }
            var permissionRefresh by remember { mutableIntStateOf(0) }
            var pendingInstallApk by remember { mutableStateOf<File?>(null) }
            var showInstallPermissionDialog by remember { mutableStateOf(false) }
            var blockedInstallAssessment by remember { mutableStateOf<ApkInstallAssessment?>(null) }
            var pendingFastSelfUpdate by remember { mutableStateOf(false) }

            Zip2ApkTheme(themeMode = themeMode, materialYou = materialYou) {
                SyncSystemBars(this@MainActivity)

                val state by viewModel.state.collectAsStateWithLifecycle()
                val isBusy = state.isBusy
                val compatibilityChecker = remember { ApkCompatibilityChecker(this@MainActivity) }

                // During long-running work, Back backgrounds the task instead of finishing
                // the Activity. Explicit removal from Recents remains the cancellation gesture.
                BackHandler(enabled = isBusy) { moveTaskToBack(true) }

                val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { viewModel.importSourceZip(it) }
                }
                val toolchainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(viewModel::importToolchainZip)
                }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    permissionRefresh++
                    if (pendingFastSelfUpdate) {
                        val granted = canReadDownloadsForQuickUpdate()
                        pendingFastSelfUpdate = false
                        if (granted) viewModel.fastSelfUpdateFromDownloads()
                        else Toast.makeText(this@MainActivity, "Downloads access is required for fast self-update.", Toast.LENGTH_LONG).show()
                    }
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (pendingFastSelfUpdate) {
                        pendingFastSelfUpdate = false
                        if (granted) viewModel.fastSelfUpdateFromDownloads()
                        else Toast.makeText(this@MainActivity, "Downloads access is required for fast self-update.", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(state.phase) {
                    val needsBuildNotification = state.phase in setOf(
                        BuildPhase.IMPORTING, BuildPhase.ANALYZING, BuildPhase.FIXING, BuildPhase.BUILDING
                    )
                    if (needsBuildNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                DisposableEffect(isBusy, keepScreenOn) {
                    if (isBusy && keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                permissionRefresh
                val installPermissionGranted = canInstallPackages(this@MainActivity)
                val openInstallPermissionSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        permissionLauncher.launch(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                val installApk: (File) -> Unit = { apk ->
                    val assessment = compatibilityChecker.assess(apk)
                    if (!assessment.canAttemptInstall) {
                        blockedInstallAssessment = assessment
                    } else if (canInstallPackages(this@MainActivity)) {
                        ApkActions.install(this@MainActivity, apk)
                    } else {
                        pendingInstallApk = apk
                        showInstallPermissionDialog = true
                    }
                }

                LaunchedEffect(permissionRefresh, installPermissionGranted) {
                    if (installPermissionGranted) {
                        pendingInstallApk?.let { apk ->
                            pendingInstallApk = null
                            val assessment = compatibilityChecker.assess(apk)
                            if (assessment.canAttemptInstall) ApkActions.install(this@MainActivity, apk)
                            else blockedInstallAssessment = assessment
                        }
                    }
                }

                LaunchedEffect(state.phase, state.apk, autoInstall) {
                    val apk = state.apk
                    if (state.phase == BuildPhase.SUCCESS && apk != null && autoInstall) {
                        installApk(apk)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnimatedContent(
                        targetState = rootScreen,
                        label = "root-screen-transition"
                    ) { screen ->
                        when (screen) {
                        SCREEN_ONBOARDING -> OnboardingScreen(
                            state = state,
                            onSetupToolchain = viewModel::setupToolchain,
                            onFinish = {
                                viewModel.clearLog()
                                preferences.onboardingComplete = true
                                rootScreen = SCREEN_MAIN
                            }
                        )

                        SCREEN_SETTINGS -> SettingsHost(
                            page = settingsPage,
                            state = state,
                            themeMode = themeMode,
                            materialYou = materialYou,
                            autoInstall = autoInstall,
                            autoBuildAfterPreparation = autoBuildAfterPreparation,
                            keepScreenOn = keepScreenOn,
                            autoFixEnabled = autoFixEnabled,
                            onBack = {
                                if (settingsPage == SETTINGS_ROOT) rootScreen = SCREEN_MAIN
                                else settingsPage = SETTINGS_ROOT
                            },
                            onOpenPage = { settingsPage = it },
                            onThemeMode = {
                                preferences.themeMode = it
                                themeMode = it
                            },
                            onMaterialYou = {
                                preferences.materialYouEnabled = it
                                materialYou = it
                            },
                            onAutoInstall = {
                                preferences.autoInstallAfterBuild = it
                                autoInstall = it
                            },
                            onAutoBuildAfterPreparation = {
                                preferences.autoBuildAfterPreparation = it
                                autoBuildAfterPreparation = it
                            },
                            onKeepScreenOn = {
                                preferences.keepScreenOnDuringBuild = it
                                keepScreenOn = it
                            },
                            onAutoFix = {
                                preferences.autoFixEnabled = it
                                autoFixEnabled = it
                            },
                            onSetupToolchain = viewModel::setupToolchain,
                            onImportToolchain = {
                                toolchainPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            onClearWorkspaces = viewModel::clearWorkspaces,
                            onClearGradleCache = viewModel::clearGradleCache,
                            onRefreshVersions = viewModel::refreshComponentVersions,
                            onRunOnboarding = {
                                preferences.onboardingComplete = false
                                settingsPage = SETTINGS_ROOT
                                rootScreen = SCREEN_ONBOARDING
                            }
                        )

                        SCREEN_HISTORY -> ApkHistoryScreen(
                            apks = state.apkHistory,
                            onBack = { rootScreen = SCREEN_MAIN },
                            onRefresh = viewModel::refreshApkHistory,
                            onInstall = installApk,
                            onShare = { ApkActions.share(this@MainActivity, it) }
                        )

                        else -> MainScreen(
                            state = state,
                            onOpenHistory = {
                                viewModel.refreshApkHistory()
                                rootScreen = SCREEN_HISTORY
                            },
                            onFastSelfUpdate = {
                                when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                                        pendingFastSelfUpdate = true
                                        permissionLauncher.launch(
                                            Intent(
                                                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:$packageName")
                                            )
                                        )
                                    }
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                                        pendingFastSelfUpdate = true
                                        storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    }
                                    else -> viewModel.fastSelfUpdateFromDownloads()
                                }
                            },
                            onOpenSettings = {
                                settingsPage = SETTINGS_ROOT
                                rootScreen = SCREEN_SETTINGS
                            },
                            onPickSource = {
                                sourcePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            onBuild = viewModel::buildApk,
                            onClearLog = viewModel::clearLog
                        )
                        }
                    }
                }

                if (showInstallPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showInstallPermissionDialog = false },
                        icon = { Icon(Icons.Rounded.Android, contentDescription = null) },
                        title = { Text("Permission required") },
                        text = {
                            Text("Android requires Zip2APK to be allowed to install unknown apps before a built APK can be opened in the package installer.")
                        },
                        confirmButton = {
                            Button(onClick = {
                                showInstallPermissionDialog = false
                                openInstallPermissionSettings()
                            }) { Text("Open settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showInstallPermissionDialog = false
                                pendingInstallApk = null
                            }) { Text("Not now") }
                        }
                    )
                }

                blockedInstallAssessment?.let { assessment ->
                    AlertDialog(
                        onDismissRequest = { blockedInstallAssessment = null },
                        icon = { Icon(Icons.Rounded.Android, contentDescription = null) },
                        title = {
                            Text(
                                when (assessment.kind) {
                                    ApkInstallCompatibilityKind.SIGNATURE_MISMATCH -> "Update signature mismatch"
                                    ApkInstallCompatibilityKind.DOWNGRADE -> "Downgrade blocked"
                                    ApkInstallCompatibilityKind.INVALID_APK -> "Invalid APK"
                                    else -> "Installation check failed"
                                }
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(assessment.message)
                                if (assessment.kind == ApkInstallCompatibilityKind.SIGNATURE_MISMATCH) {
                                    assessment.installedCertificateSha256?.let {
                                        Text("Installed: ${shortCertificate(it)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                    assessment.targetCertificateSha256?.let {
                                        Text("APK: ${shortCertificate(it)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { blockedInstallAssessment = null }) { Text("Close") }
                        },
                        dismissButton = {
                            if (assessment.kind == ApkInstallCompatibilityKind.SIGNATURE_MISMATCH && assessment.packageName == packageName) {
                                TextButton(onClick = {
                                    ApkActions.share(this@MainActivity, assessment.apk)
                                    blockedInstallAssessment = null
                                }) { Text("Share APK") }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun canReadDownloadsForQuickUpdate(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SCREEN_MAIN = "main"
        private const val SCREEN_SETTINGS = "settings"
        private const val SCREEN_ONBOARDING = "onboarding"
        private const val SCREEN_HISTORY = "history"
    }
}

private val BuilderUiState.isBusy: Boolean
    get() = phase in setOf(
        BuildPhase.TOOLCHAIN_SETUP,
        BuildPhase.IMPORTING,
        BuildPhase.ANALYZING,
        BuildPhase.FIXING,
        BuildPhase.BUILDING
    ) || busyMessage != null

private fun canInstallPackages(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

private fun shortCertificate(value: String): String {
    val parts = value.split(':')
    return if (parts.size > 8) parts.take(8).joinToString(":") + "…" else value
}

@Suppress("DEPRECATION")
@Composable
private fun SyncSystemBars(activity: ComponentActivity) {
    val background = MaterialTheme.colorScheme.background
    val navigation = MaterialTheme.colorScheme.background
    val lightIcons = background.luminance() > 0.5f
    SideEffect {
        activity.window.statusBarColor = background.toArgb()
        activity.window.navigationBarColor = navigation.toArgb()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    state: BuilderUiState,
    onOpenHistory: () -> Unit,
    onFastSelfUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickSource: () -> Unit,
    onBuild: () -> Unit,
    onClearLog: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Zip2APK", fontWeight = FontWeight.Bold) },
                actions = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = !state.isBusy,
                                role = Role.Button,
                                onClickLabel = "Built APKs",
                                onLongClickLabel = "Fast self-update from Downloads",
                                onLongClick = onFastSelfUpdate,
                                onClick = onOpenHistory
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = "Built APKs. Hold for fast self-update from Downloads.")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPickSource,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select source ZIP")
            }

            Button(
                onClick = onBuild,
                enabled = !state.isBusy && state.project != null && state.toolchain?.installed == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedVisibility(visible = state.phase == BuildPhase.BUILDING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text("Build APK")
            }

            BuildLog(
                state = state,
                onClear = onClearLog,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BuildLog(
    state: BuilderUiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.lastIndex)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Build log",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                BuildStatusTag(state)
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Zip2APK build log", state.logs.joinToString("\n"))
                        )
                        Toast.makeText(context, "Build log copied", Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.logs.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy build log")
                }
                IconButton(onClick = onClear, enabled = state.logs.isNotEmpty()) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear build log")
                }
            }
            HorizontalDivider()
            Crossfade(targetState = state.logs.isEmpty(), label = "log-empty") { empty ->
                if (empty) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopStart) {
                        Text(
                            "Build output will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(state.logs) { line ->
                                Text(
                                    line,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildStatusTag(state: BuilderUiState) {
    val (label, container, content) = when (state.phase) {
        BuildPhase.IMPORTING -> Triple("Importing…", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        BuildPhase.ANALYZING -> Triple("Analyzing…", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        BuildPhase.FIXING -> Triple("Fixing…", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        BuildPhase.TOOLCHAIN_SETUP -> Triple("Setup…", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        BuildPhase.BUILDING -> {
            val text = if (state.busyMessage?.contains("Preparing", ignoreCase = true) == true) "Preparing…" else "Building…"
            Triple(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
        BuildPhase.READY -> Triple("Ready", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        BuildPhase.SUCCESS -> Triple("Success", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        BuildPhase.ERROR -> Triple("Failed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        BuildPhase.IDLE -> Triple("Idle", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    AnimatedContent(targetState = label, label = "build-status") { text ->
        Surface(
            shape = RoundedCornerShape(100.dp),
            color = container,
            contentColor = content
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(
    state: BuilderUiState,
    onSetupToolchain: () -> Unit,
    onFinish: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 3

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Zip2APK setup") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                label = "onboarding-step"
            ) { currentStep ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (currentStep) {
                        0 -> {
                            Text("Welcome to Zip2APK", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("This one-time wizard prepares the local Android build environment. After setup, source ZIPs can be built directly on this device without Android Studio.")
                            InfoCard(
                                title = "What will be configured",
                                lines = listOf(
                                    "Local ARM64 JDK and build utilities",
                                    "Android SDK platforms and build tools",
                                    "Clang, CMake and Ninja for native projects",
                                    "APK install permission will be requested only when you install a build"
                                )
                            )
                        }

                        1 -> {
                            Text("Build toolchain", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Download and prepare the local compiler, Android SDK and native build tools. This can take several minutes and requires an internet connection.")
                            ToolchainStatusCard(state)
                            if (state.toolchain?.installed != true) {
                                Button(
                                    onClick = onSetupToolchain,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AnimatedVisibility(visible = state.phase == BuildPhase.TOOLCHAIN_SETUP) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                    }
                                    Text("Set up toolchain")
                                }
                            }
                            AnimatedVisibility(visible = state.logs.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        state.logs.takeLast(10).joinToString("\n"),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            AnimatedVisibility(visible = state.error != null) {
                                Text(
                                    state.error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        else -> {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("Setup complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Zip2APK is ready. Select an Android Studio project ZIP from the main screen, then press Build APK.")
                            Text(
                                "When you install a built APK for the first time, Zip2APK will show a reminder and open Android's required permission screen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(visible = step > 0 && step < totalSteps - 1, modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { step-- },
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Back") }
                }

                when (step) {
                    0 -> Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("Continue") }
                    1 -> Button(
                        onClick = { step++ },
                        enabled = state.toolchain?.installed == true && !state.isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Continue") }
                    else -> Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ToolchainStatusCard(state: BuilderUiState) {
    val toolchain = state.toolchain
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local ARM64 build toolchain", fontWeight = FontWeight.SemiBold)
            when {
                toolchain == null -> Text("Checking…")
                toolchain.installed -> {
                    Text("Ready", color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Java and Android build tools are installed. Native build: ${if (toolchain.nativeReady) "ready" else "incomplete"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text("Needs setup", color = MaterialTheme.colorScheme.error)
                    toolchain.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun SettingsHost(
    page: String,
    state: BuilderUiState,
    themeMode: ThemeMode,
    materialYou: Boolean,
    autoInstall: Boolean,
    autoBuildAfterPreparation: Boolean,
    keepScreenOn: Boolean,
    autoFixEnabled: Boolean,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onMaterialYou: (Boolean) -> Unit,
    onAutoInstall: (Boolean) -> Unit,
    onAutoBuildAfterPreparation: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onAutoFix: (Boolean) -> Unit,
    onSetupToolchain: () -> Unit,
    onImportToolchain: () -> Unit,
    onClearWorkspaces: () -> Unit,
    onClearGradleCache: () -> Unit,
    onRefreshVersions: () -> Unit,
    onRunOnboarding: () -> Unit
) {
    AnimatedContent(
        targetState = page,
        label = "settings-page"
    ) { currentPage ->
        when (currentPage) {
            SETTINGS_APPEARANCE -> AppearanceSettings(themeMode, materialYou, onThemeMode, onMaterialYou, onBack)
            SETTINGS_BUILD -> BuildSettings(
                state, autoInstall, autoBuildAfterPreparation, keepScreenOn, autoFixEnabled,
                onAutoInstall, onAutoBuildAfterPreparation, onKeepScreenOn, onAutoFix, onBack
            )
            SETTINGS_TOOLCHAIN -> ToolchainSettings(state, onSetupToolchain, onImportToolchain, onBack)
            SETTINGS_STORAGE -> StorageSettings(onClearWorkspaces, onClearGradleCache, onBack)
            SETTINGS_ABOUT -> AboutSettings(state, onRefreshVersions, onRunOnboarding, onBack)
            else -> SettingsRoot(state, onBack, onOpenPage)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    state: BuilderUiState,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SettingsCategoryRow(Icons.Rounded.Palette, "Appearance", "Theme, Material You and visual appearance") {
                    onOpenPage(SETTINGS_APPEARANCE)
                }
                SettingsCategoryRow(Icons.Rounded.Code, "Build", "Build behavior and automatic repair") {
                    onOpenPage(SETTINGS_BUILD)
                }
                SettingsCategoryRow(
                    Icons.Rounded.Settings,
                    "Toolchain",
                    if (state.toolchain?.installed == true) "Local ARM64 build environment ready" else "Setup or repair the local build environment"
                ) { onOpenPage(SETTINGS_TOOLCHAIN) }
                SettingsCategoryRow(Icons.Rounded.Storage, "Storage & cache", "Imported projects and Gradle cache") {
                    onOpenPage(SETTINGS_STORAGE)
                }
                SettingsCategoryRow(Icons.Rounded.Info, "About", "App information and installed component versions") {
                    onOpenPage(SETTINGS_ABOUT)
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            content = content
        )
    }
}

@Composable
private fun AppearanceSettings(
    themeMode: ThemeMode,
    materialYou: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onMaterialYou: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SettingsPage("Appearance", onBack) {
        Text("Theme", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        ThemeMode.entries.forEach { mode ->
            val label = when (mode) {
                ThemeMode.SYSTEM -> "System default"
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
            }
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(selected = themeMode == mode, onClick = { onThemeMode(mode) }) },
                modifier = Modifier.clickable { onThemeMode(mode) }
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SwitchSettingRow(
            title = "Material You",
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Use Android’s current wallpaper-derived system color palette."
            } else {
                "Dynamic colors require Android 12 or newer."
            },
            checked = materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onCheckedChange = onMaterialYou
        )
    }
}

@Composable
private fun BuildSettings(
    state: BuilderUiState,
    autoInstall: Boolean,
    autoBuildAfterPreparation: Boolean,
    keepScreenOn: Boolean,
    autoFixEnabled: Boolean,
    onAutoInstall: (Boolean) -> Unit,
    onAutoBuildAfterPreparation: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onAutoFix: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SettingsPage("Build", onBack) {
        SwitchSettingRow(
            "Build immediately after preparation",
            "Start building automatically after the selected source ZIP has been imported, analyzed and prepared.",
            autoBuildAfterPreparation,
            onCheckedChange = onAutoBuildAfterPreparation
        )
        SwitchSettingRow(
            "Install after successful build",
            "Open Android's package installer when an APK is produced. Permission is requested only when needed.",
            autoInstall,
            onCheckedChange = onAutoInstall
        )
        SwitchSettingRow(
            "Keep screen on while building",
            "Prevent the device from sleeping during imports, setup and builds.",
            keepScreenOn,
            onCheckedChange = onKeepScreenOn
        )
        SwitchSettingRow(
            "Safe automatic fixes",
            "Repair recognized SDK/Gradle environment problems and retry failed builds without rewriting application logic.",
            autoFixEnabled,
            onCheckedChange = onAutoFix
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Self-update signing",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall
        )
        val signing = state.signingIdentity
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        signing == null -> "Checking…"
                        signing.matchesInstalledApp -> "Established"
                        signing.available -> "One-time migration required"
                        else -> "Unavailable"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        signing?.matchesInstalledApp == true -> MaterialTheme.colorScheme.primary
                        signing?.available == true -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    signing?.message ?: "Inspecting the persistent signing identity…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                signing?.certificateSha256?.let { fingerprint ->
                    Text(
                        "Certificate  ${shortCertificate(fingerprint)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "Zip2APK self-builds reuse this key automatically. Changing it prevents Android from installing the next APK as an update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToolchainSettings(
    state: BuilderUiState,
    onSetupToolchain: () -> Unit,
    onImportToolchain: () -> Unit,
    onBack: () -> Unit
) {
    SettingsPage("Toolchain", onBack) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ToolchainStatusCard(state) }
        Button(
            onClick = onSetupToolchain,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(if (state.toolchain?.installed == true) "Repair toolchain" else "Set up toolchain")
        }
        OutlinedButton(
            onClick = onImportToolchain,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Import toolchain bundle (advanced)")
        }
        AnimatedVisibility(visible = state.phase == BuildPhase.TOOLCHAIN_SETUP) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun StorageSettings(
    onClearWorkspaces: () -> Unit,
    onClearGradleCache: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    SettingsPage("Storage & cache", onBack) {
        ListItem(
            headlineContent = { Text("Imported project workspaces") },
            supportingContent = { Text("Delete extracted source projects and clear the current project selection.") },
            leadingContent = { Icon(Icons.Rounded.Storage, contentDescription = null) }
        )
        OutlinedButton(
            onClick = {
                onClearWorkspaces()
                Toast.makeText(context, "Project workspaces cleared", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("Clear project workspaces") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        ListItem(
            headlineContent = { Text("Gradle cache") },
            supportingContent = { Text("Delete downloaded Gradle/Maven cache. Dependencies will be downloaded again on the next build.") },
            leadingContent = { Icon(Icons.Rounded.Delete, contentDescription = null) }
        )
        OutlinedButton(
            onClick = {
                onClearGradleCache()
                Toast.makeText(context, "Gradle cache cleared", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("Clear Gradle cache") }
    }
}

@Composable
private fun AboutSettings(
    state: BuilderUiState,
    onRefreshVersions: () -> Unit,
    onRunOnboarding: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val creator = stringResource(R.string.about_creator)
    val repository = stringResource(R.string.about_github_url)
    val license = stringResource(R.string.about_license)
    val description = stringResource(R.string.about_description)

    LaunchedEffect(Unit) { onRefreshVersions() }

    SettingsPage("About", onBack) {
        ListItem(
            headlineContent = { Text("Zip2APK") },
            supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
            leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) }
        )
        Text(description, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ListItem(headlineContent = { Text("Creator") }, supportingContent = { Text(creator) })
        ListItem(
            headlineContent = { Text("GitHub repository") },
            supportingContent = { Text(repository, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickable(enabled = repository.startsWith("http")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repository))) }
            }
        )
        ListItem(headlineContent = { Text("License") }, supportingContent = { Text(license) })

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Installed components", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (state.componentVersions.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Reading installed versions…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            state.componentVersions.forEach { component ->
                ListItem(
                    headlineContent = { Text(component.name) },
                    supportingContent = { Text(component.version) }
                )
            }
        }

        OutlinedButton(
            onClick = onRunOnboarding,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) { Text("Run setup wizard again") }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ApkHistoryScreen(
    apks: List<ApkArtifact>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit
) {
    var selectedDetails by remember { mutableStateOf<ApkArtifact?>(null) }
    LaunchedEffect(Unit) { onRefresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Built APKs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Crossfade(targetState = apks.isEmpty(), label = "apk-history-empty") { empty ->
            if (empty) {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(42.dp))
                        Text("No APKs built yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Successful builds are kept here for quick install and sharing.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(apks, key = { it.file.absolutePath }) { apk ->
                        ListItem(
                            headlineContent = {
                                Text(apk.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        listOfNotNull(apk.packageName, apk.versionName?.let { "v$it" }).joinToString(" • ").ifBlank { "APK" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${formatFileSize(apk.sizeBytes)} • ${formatDate(apk.builtAtMillis)} • Hold for details",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            leadingContent = { Icon(Icons.Rounded.Android, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { onShare(apk.file) }) {
                                    Icon(Icons.Rounded.Share, contentDescription = "Share APK")
                                }
                            },
                            modifier = Modifier
                                
                                .combinedClickable(
                                    onClick = { onInstall(apk.file) },
                                    onLongClick = { selectedDetails = apk }
                                )
                        )
                    }
                }
            }
        }
    }

    selectedDetails?.let { apk ->
        ApkDetailsDialog(
            apk = apk,
            onDismiss = { selectedDetails = null },
            onInstall = {
                selectedDetails = null
                onInstall(apk.file)
            },
            onShare = { onShare(apk.file) }
        )
    }
}

@Composable
private fun ApkDetailsDialog(
    apk: ApkArtifact,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Android, contentDescription = null) },
        title = { Text(apk.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailLine("Package", apk.packageName ?: "Unknown")
                    DetailLine("Version", apk.versionName ?: "Unknown")
                    DetailLine("Version code", apk.versionCode?.toString() ?: "Unknown")
                    DetailLine("Min SDK", apk.minSdk?.toString() ?: "Unknown")
                    DetailLine("Target SDK", apk.targetSdk?.toString() ?: "Unknown")
                    DetailLine("Size", formatFileSize(apk.sizeBytes))
                    DetailLine("Built", formatDate(apk.builtAtMillis))
                    DetailLine("SHA-256", apk.sha256)
                    DetailLine("Path", apk.file.absolutePath)
                }
            }
        },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

private const val SETTINGS_ROOT = "root"
private const val SETTINGS_APPEARANCE = "appearance"
private const val SETTINGS_BUILD = "build"
private const val SETTINGS_TOOLCHAIN = "toolchain"
private const val SETTINGS_STORAGE = "storage"
private const val SETTINGS_ABOUT = "about"
