import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Rawr 17U fork: Rawr Remote, the Wear OS companion of Watch Remote. Google Play services only connects a phone app and a
// watch app that have the same application id AND the same signature, so both follow :app exactly: the same ids per build
// type and the same debug-key lookup (signing/debug.properties or RAWR_DEBUG_SIGNING_*, then a root debug.keystore).
androidComponents {
    onVariants(selector().withBuildType("debug")) { it.applicationId.set("x17u.rawr.camera.debug") }
    onVariants(selector().withBuildType("release")) { it.applicationId.set("x17u.rawr.camera") }
}

android {
    namespace = "com.rawr.camera.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "x17u.rawr.camera"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    val localDebugSigning = Properties().apply {
        rootProject.file("signing/debug.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
    }
    val debugSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { key ->
        providers.gradleProperty("rawr.debugSigning.$key").orElse(providers.environmentVariable("RAWR_DEBUG_SIGNING_$key")).orNull
            ?: localDebugSigning.getProperty("rawr.debugSigning.$key")
    }
    if (debugSigning.values.all { it != null }) {
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file(requireNotNull(debugSigning["STORE_FILE"]))
            storePassword = debugSigning["STORE_PASSWORD"]
            keyAlias = debugSigning["KEY_ALIAS"]
            keyPassword = debugSigning["KEY_PASSWORD"]
        }
    }
    // Release: the same key as :app's release (RAWR_SIGNING_* or signing/release.properties), as Play services requires.
    val localSigning = Properties().apply {
        rootProject.file("signing/release.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
    }
    val releaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { key ->
        providers.gradleProperty("rawr.signing.$key").orElse(providers.environmentVariable("RAWR_SIGNING_$key")).orNull
            ?: localSigning.getProperty("rawr.signing.$key")
    }
    if (releaseSigning.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(requireNotNull(releaseSigning["STORE_FILE"]))
            storePassword = releaseSigning["STORE_PASSWORD"]
            keyAlias = releaseSigning["KEY_ALIAS"]
            keyPassword = releaseSigning["KEY_PASSWORD"]
        }
    }
    if (rootProject.file("debug.keystore").exists()) {
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
}
