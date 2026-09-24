# Zip2APK
<img width="235" height="470" alt="imatge" src="https://github.com/user-attachments/assets/bcd7cda5-8a67-4eb7-9c3c-8aff2e2507b8" />

Zip2APK is a Jetpack Compose Android app that turns a normal Android Studio / Gradle source ZIP into an installable debug APK directly on an ARM64 Android device.

The basic idea is to choose a zip and the app builds it into an APK with one click


```text
project.zip
   ↓
secure extraction
   ↓
project inspection + deterministic auto-fixes
   ↓
local JDK / Gradle / Android SDK / aapt2
   ↓
variant-aware native dependency resolution + Android-native external build backend
   ↓
assembleDebug
   ↓
APK → Install / Share
```

## What is implemented

- Storage Access Framework ZIP picker.
- Zip-slip protected extraction to an app-private workspace.
- Gradle project-root and Android-module detection.
- Java, Kotlin, C/C++, CMake, AGP, Gradle, SDK and package/namespace inspection.
- Automatic ARM64 build-environment setup from the official Termux package infrastructure.
- Resilient package bootstrap: required packages are retried individually, and Android packaging tools use the current Termux `aapt` bundle first with legacy `aapt2` / `zipalign` / `aidl` fallbacks.
- OpenJDK 17, Android-native aapt2/zipalign (and aidl when available), Clang, CMake, Ninja, ndk-sysroot, Make and common command-line utilities.
- Android command-line tools and automatic SDK platform installation for the imported project's `compileSdk` / `targetSdk`, with Android-native host-tool overlays for ARM64.
- Project Gradle-wrapper execution with a local Gradle fallback when available.
- Maven/Google dependency resolution through normal Gradle repositories and persistent Gradle caching.
- Normal project debug signing through AGP, plus persistent self-update signing for Zip2APK itself so future self-builds keep the same Android signing identity.
- Pluggable native-build architecture with a CMake backend, Gradle-owned AAR dependency resolution, Google Prefab CLI integration, and generated `.so` hand-off through `jniLibs`.
- Up to three Gradle build attempts with deterministic repair between attempts.
- APK discovery, Android package-installer handoff, and sharing through `FileProvider`.
- First-run onboarding wizard for local toolchain setup; APK-install permission is requested only when an install is attempted.
- Minimal main screen with source selection, Build APK, APK history/settings actions, and a copy/clear build log with an animated build-status tag.
- Consolidated Settings UI for appearance, build behavior/automatic fixes, toolchain, storage/cache, and app information.
- Persistent theme/build/fix preferences.
- Live Compose build log with copy and clear actions.

## Automatic repairs

Zip2APK changes only the extracted private workspace, never the source ZIP. It automatically repairs issues with a deterministic environment/configuration correction:

- missing or stale `sdk.dir`;
- stale desktop `org.gradle.java.home`;
- x86_64 Maven `aapt2` selection on the ARM64 Android host;
- missing Android `namespace` when it can be inferred;
- missing `android.useAndroidX=true` when AndroidX dependencies are detected;
- a Gradle wrapper version below an explicitly reported minimum;
- for CMake projects, Gradle `externalNativeBuild` wiring after the native libraries have already been built locally and copied to `jniLibs`.

It deliberately does not guess changes to Java/Kotlin/C++ application logic or arbitrarily upgrade third-party dependencies.

## Important package-ID decision

This edition uses:

```text
applicationId = com.termux
namespace     = com.zip2apk.builder
```

That is intentional. Official Termux ARM64 packages are compiled for the fixed prefix:

```text
/data/data/com.termux/files/usr
```

Using that package ID means the official Termux bootstrap and repository binaries can run directly inside Zip2APK instead of maintaining a separate rebuilt compiler repository.

**Consequence: Zip2APK cannot coexist with the official Termux app under the normal Android package manager.** If Termux is already installed, Android will treat Zip2APK as the same package with a different signing key. Uninstall Termux first, or build your own complete toolchain repository for a different application ID.

## First run

The first launch opens a setup wizard instead of the normal builder screen:

1. **Welcome** explains what Zip2APK will configure and that APK-install permission is requested only when it is actually needed.
2. **Build toolchain** downloads and prepares the local ARM64 JDK, Android SDK, aapt2, Clang, CMake, Ninja and supporting packages. The wizard cannot finish until the build toolchain is ready.
3. **Ready** completes onboarding and opens the minimal main screen.

After onboarding, the main screen intentionally contains only the Zip2APK title with APK-history/settings actions, **Select source ZIP**, **Build APK**, and the build log. By default, a successful build opens Android's package installer; this can be disabled in **Settings → Build**. If Android's per-app "Install unknown apps" access has not been granted, Zip2APK displays a reminder dialog at install time and opens the required system settings page. Source ZIP access uses the system document picker, so broad storage permission is not requested.

The initial setup is large and requires Internet access. Gradle dependencies may also require Internet access on the first build. Gradle's cache is retained for subsequent builds.


## C/C++ native-build architecture

Zip2APK does not execute Google's desktop-host NDK binaries on the phone. Version 0.5.x separates native handling into three layers instead: a planner discovers configured Android native modules and preserves literal Gradle CMake options; Gradle itself resolves the module's real dependency graph; then a build-system backend runs Android-native host tools and hands the resulting `.so` files back to AGP through `jniLibs`.

For CMake projects, Maven/AAR dependencies are no longer disconnected from the standalone CMake invocation. Zip2APK asks the project's own Gradle configuration for the original AAR artifacts (explicitly requesting `artifactType=aar` so AGP classpath transforms cannot hide them), extracts every AAR that exposes a Prefab package, and runs Google's pinned Prefab CLI to generate the same kind of CMake config packages consumed by `find_package(...)`. This is dependency-name agnostic: Oboe, GameActivity, curl/OpenSSL packages, fbjni, and private/custom Prefab AARs all travel through the same bridge. Required shared Prefab libraries are also staged into the APK automatically.

The current CMake backend produces **arm64-v8a**. Current Termux native packages have an API-24 baseline, so Zip2APK refuses to silently compile a project declaring native `minSdk < 24` while leaving its manifest unchanged. Literal `arguments`, `cFlags`, `cppFlags`, `targets`, `abiFilters`, STL selection, and non-default CMakeLists paths are preserved where they can be determined without executing AGP's desktop native tasks. Dependency resolution is scoped per native Gradle module, and the official Prefab CLI's generated imported targets are used as the source of truth for selected shared libraries. Zip2APK recognizes `ndk-build`/`Android.mk` as a separate native backend, but the current release deliberately reports that backend as unsupported rather than attempting an unsafe CMake translation. Projects that require NDK host utilities/code generators, another ABI, project-local Prefab producer builds, or other custom native systems can still require additional backend support.

## Security

A Gradle ZIP is executable input. `build.gradle(.kts)`, Gradle plugins, annotation processors, scripts and CMake files can execute code while building. Only compile projects you trust. Android's app sandbox limits the build to Zip2APK's app identity, but Zip2APK does not add a second sandbox around Gradle itself.

Zip2APK 0.4.3+ also maintains a per-install private signing identity for its own self-updates. The desktop project creates it automatically under `.zip2apk-signing/`, which is Git-ignored, and the installed app keeps a copy in app-private storage. Because the app must be able to recover the key after installation, the key is also carried inside Zip2APK's own APK. This is a deliberate sideload/developer-tool trade-off; do not use this scheme as a public production-app signing model. See `SELF_UPDATE_SIGNING.md`.

## Building Zip2APK on a desktop

The app project currently uses:

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk 37
- targetSdk 28 (intentional for the sideloaded local-executable model)
- Jetpack Compose / Material 3

If `gradle/wrapper/gradle-wrapper.jar` is not present in your checkout, run `bootstrap-wrapper.sh` / `bootstrap-wrapper.ps1` or generate the Gradle 9.6.0 wrapper with a locally installed Gradle.

On the first Gradle sync/build, the project automatically creates a personal update-signing identity under `.zip2apk-signing/`. Keep that directory if you continue building Zip2APK from the same desktop checkout. It is excluded from Git. Once Zip2APK 0.4.3+ is installed, Android-side self-builds automatically reuse the installed app's persistent key instead.


### 0.3.2 Android SDK platform fix

Android 17 / API 37 is published by the current SDK repository as `platforms;android-37.0`, not `platforms;android-37`. Zip2APK now maps API 37 to that package name and verifies installation by checking for `platforms/android-37.0/android.jar`. It retains the legacy name as a fallback for older repository layouts.

The first-run wizard now installs only Android API 35 as a baseline platform. Additional `compileSdk` / `targetSdk` platforms are installed on demand after a source project is selected. This prevents a newly published platform naming change from blocking the entire onboarding flow. SDK package installs are performed separately and verified on disk so one unavailable package cannot silently poison the rest of the setup.

### 0.3.1 setup fix

The installer no longer treats `zipalign` as a mandatory standalone Termux package. Some repository snapshots expose Android packaging tools through the `aapt` bundle instead. Setup now installs the current bundle first, falls back to legacy split package names, and only requires native `aapt2` for toolchain readiness. A missing optional `zipalign` or `aidl` no longer aborts the entire first-run wizard.

## Source layout

```text
app/src/main/java/com/zip2apk/builder/
├── MainActivity.kt
├── BuilderViewModel.kt
├── build/
│   ├── ZipProjectImporter.kt
│   ├── ProjectAnalyzer.kt
│   ├── AutoFixEngine.kt
│   ├── TermuxEnvironment.kt
│   ├── ToolchainManager.kt
│   ├── NativePrebuilder.kt
│   ├── NativeBuildPlanner.kt
│   ├── NativeBuildBackend.kt
│   ├── CMakeNativeBackend.kt
│   ├── NativeDependencyResolver.kt
│   ├── PrefabIntegrator.kt
│   ├── GradleBuildRunner.kt
│   ├── BuildCoordinator.kt
│   └── ApkLocator.kt
├── model/Models.kt
├── ui/Theme.kt
└── util/
    ├── ApkActions.kt
    ├── ApkCompatibilityChecker.kt
    ├── ApkHistoryStore.kt
    ├── SelfSigningManager.kt
    └── AppPreferences.kt
```
update log
## 0.5.0 native dependency architecture

Version 0.5.0 replaces the one-off standalone-CMake path with a backend-oriented native build pipeline. Gradle remains responsible for repository and dependency semantics on a per-module basis; resolved AARs are inspected for Prefab; Google's Prefab CLI 2.1.0 is embedded as a JVM tool and used to generate CMake integration; the CLI-generated imported targets determine which shared native dependencies are staged for APK packaging; and only after all native modules succeed is AGP's desktop-host `externalNativeBuild` wiring disabled in the extracted workspace. The project analyzer also no longer treats every directory named `build` as generated output, preventing legitimate source packages such as `com/.../builder/build` from disappearing during analysis or source packaging. See `NATIVE_BUILD_ARCHITECTURE_0.5.0.md`.

## 0.5.6 native Gradle handoff

After a successful Android-native prebuild, Zip2APK now relies solely on the rewritten `jniLibs` handoff and runs a plain `assembleDebug`. It no longer passes `-x externalNativeBuildDebug`, because removing the `externalNativeBuild` DSL also removes that task and Gradle treats excluding a nonexistent task as a fatal task-selection error. This also avoids hard-coding AGP task names across variants and plugin versions. See `NATIVE_GRADLE_HANDOFF_0.5.6.md`.

## 0.5.7 background build and quick self-update

Version 0.5.7 adds build-after-preparation (enabled by default), process-wide active build state, a foreground build service with progress/failure notifications and a partial wake lock, and explicit cancellation when the user removes Zip2APK from Recents. Long-pressing the APK-history action now scans Downloads for a complete, strictly newer Zip2APK source ZIP and immediately runs the normal secure import/build/self-sign pipeline. See `BACKGROUND_AND_QUICK_UPDATE_0.5.7.md`.
