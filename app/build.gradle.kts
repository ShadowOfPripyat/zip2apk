import java.io.File
import java.security.SecureRandom
import java.util.Properties
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep Google's Prefab CLI as an opaque JVM asset. It is executed by the bundled
// OpenJDK on-device and is not dexed into the Android application.
val prefabCli by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun escapeBuildConfig(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val externalSigningStore = providers.gradleProperty("zip2apk.selfSignStore").orNull?.let { file(it) }
val externalStorePassword = providers.gradleProperty("zip2apk.selfSignStorePassword").orNull
val externalKeyAlias = providers.gradleProperty("zip2apk.selfSignKeyAlias").orNull
val externalKeyPassword = providers.gradleProperty("zip2apk.selfSignKeyPassword").orNull

val localSigningDir = rootProject.file(".zip2apk-signing").apply { mkdirs() }
val localSigningPropertiesFile = File(localSigningDir, "signing.properties")
val localSigningStore = File(localSigningDir, "zip2apk-update.p12")

val localSigningProperties = Properties()
if (localSigningPropertiesFile.isFile) {
    localSigningPropertiesFile.inputStream().use(localSigningProperties::load)
}

if (externalSigningStore == null && (!localSigningStore.isFile || !localSigningPropertiesFile.isFile)) {
    val password = randomHex(24)
    val alias = "zip2apk-update"
    val javaHome = File(System.getProperty("java.home"))
    val keytoolName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "keytool.exe" else "keytool"
    val keytool = File(javaHome, "bin/$keytoolName")
    check(keytool.isFile) {
        "Unable to create Zip2APK's persistent signing identity because keytool was not found at ${keytool.absolutePath}."
    }

    localSigningDir.mkdirs()
    localSigningStore.delete()
    val process = ProcessBuilder(
        keytool.absolutePath,
        "-genkeypair",
        "-keystore", localSigningStore.absolutePath,
        "-storetype", "PKCS12",
        "-storepass", password,
        "-keypass", password,
        "-alias", alias,
        "-keyalg", "RSA",
        "-keysize", "3072",
        "-validity", "36500",
        "-dname", "CN=Zip2APK Self Update, OU=Local Build, O=Zip2APK, C=XX"
    )
        .inheritIO()
        .start()
    check(process.waitFor() == 0 && localSigningStore.isFile) {
        "keytool failed while creating Zip2APK's persistent update-signing identity."
    }

    localSigningProperties.setProperty("storePassword", password)
    localSigningProperties.setProperty("keyPassword", password)
    localSigningProperties.setProperty("keyAlias", alias)
    localSigningPropertiesFile.outputStream().use { localSigningProperties.store(it, "Zip2APK private signing identity - do not publish") }
}

if (localSigningPropertiesFile.isFile && localSigningProperties.isEmpty) {
    localSigningPropertiesFile.inputStream().use(localSigningProperties::load)
}

val activeSigningStore = externalSigningStore ?: localSigningStore
val activeStorePassword = requireNotNull(
    if (externalSigningStore != null) externalStorePassword else localSigningProperties.getProperty("storePassword")
) { "Zip2APK signing store password is missing." }
    .also { check(it.isNotBlank()) { "Zip2APK signing store password is empty." } }
val activeKeyAlias = if (externalSigningStore != null) {
    externalKeyAlias ?: "zip2apk-update"
} else {
    localSigningProperties.getProperty("keyAlias") ?: "zip2apk-update"
}
val activeKeyPassword = requireNotNull(
    if (externalSigningStore != null) externalKeyPassword ?: externalStorePassword
    else localSigningProperties.getProperty("keyPassword") ?: activeStorePassword
) { "Zip2APK signing key password is missing." }
    .also { check(it.isNotBlank()) { "Zip2APK signing key password is empty." } }

check(activeSigningStore.isFile) { "Zip2APK signing keystore is missing: ${activeSigningStore.absolutePath}" }

// Every Zip2APK APK carries the same per-install signing identity that signed it.
// This is required so the installed app can self-build future versions without
// losing the private key after the original Android Studio project is gone.
val generatedSigningAssets = layout.buildDirectory.dir("generated/zip2apk-signing-assets").get().asFile
val prepareSigningAsset = tasks.register<Copy>("prepareZip2ApkSigningAsset") {
    from(activeSigningStore)
    into(File(generatedSigningAssets, "signing"))
    rename { "zip2apk-update.p12" }
}

val generatedToolAssets = layout.buildDirectory.dir("generated/zip2apk-tool-assets").get().asFile
val preparePrefabCliAsset = tasks.register<Copy>("prepareZip2ApkPrefabCliAsset") {
    from(prefabCli)
    into(File(generatedToolAssets, "tools"))
    rename { "prefab-cli-2.1.0-all.jar" }
}

android {
    namespace = "com.zip2apk.builder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.termux"
        minSdk = 26
        // Intentionally 28: see README. This enables the local, sideload-only
        // executable toolchain model used by Termux-like developer tools.
        targetSdk = 28
        versionCode = 20
        versionName = "0.5.7"

        buildConfigField("String", "ZIP2APK_UPDATE_STORE_PASSWORD", "\"${escapeBuildConfig(activeStorePassword)}\"")
        buildConfigField("String", "ZIP2APK_UPDATE_KEY_PASSWORD", "\"${escapeBuildConfig(activeKeyPassword)}\"")
        buildConfigField("String", "ZIP2APK_UPDATE_KEY_ALIAS", "\"${escapeBuildConfig(activeKeyAlias)}\"")
        buildConfigField("String", "ZIP2APK_UPDATE_KEY_ASSET", "\"signing/zip2apk-update.p12\"")
    }

    signingConfigs {
        create("zip2apkUpdate") {
            storeFile = activeSigningStore
            storePassword = activeStorePassword
            keyAlias = activeKeyAlias
            keyPassword = activeKeyPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("zip2apkUpdate")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("zip2apkUpdate")
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedSigningAssets)
        getByName("main").assets.srcDir(generatedToolAssets)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Ensure the embedded key exists even for a single `clean assembleDebug` invocation.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(prepareSigningAsset)
        dependsOn(preparePrefabCliAsset)
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    add(prefabCli.name, "com.google.prefab:cli:2.1.0:all@jar")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
