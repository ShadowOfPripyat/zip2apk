# Validation notes

The build-engine Kotlin sources were compiled with lightweight Android API stubs to catch Kotlin syntax/type errors outside the UI layer. This covers project analysis, auto-fix, bootstrap/toolchain management, native prebuild, Gradle launching and build coordination.

An end-to-end APK build cannot be executed inside this repository-generation environment because it is not an ARM64 Android process and does not expose Android's package/data-directory runtime. The on-device bootstrap and compiler path therefore must be exercised on an ARM64 Android device for final runtime validation.

Known intentional constraints:

- sideload-only targetSdk 28 execution model;
- applicationId `com.termux`, so it cannot coexist with official Termux;
- automatic toolchain setup requires Internet access;
- CMake output is currently arm64-v8a only;
- exotic NDK/CMake/Prefab projects can require project-specific handling.

## 0.5.4

Added the official Android NDK target-sysroot bridge for CMake builds. Native projects now get API/ABI-specific platform linker stubs and matching libc++ while continuing to execute only Android-native Termux host tools.

## 0.5.7

Added automatic build-after-preparation, foreground/background build continuity, progress/failure notifications, and long-press Downloads-based fast self-update with strict versionCode gating.
