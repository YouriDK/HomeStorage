import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// `boxpix.version` in gradle.properties is the single source of truth. The name
// stays clean semver (Play shows it, and FreeboxAppIdentity sends it to the box
// as app_version); the code is derived so it can only ever grow.
val appVersionName: String = providers.gradleProperty("boxpix.version").get().trim()

val appVersionCode: Int = appVersionName.split(".").let { parts ->
    require(parts.size == 3) { "boxpix.version must be MAJOR.MINOR.PATCH, got '$appVersionName'" }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: error("boxpix.version has a non-numeric part: '$appVersionName'")
    }
    require(minor in 0..99 && patch in 0..99) {
        "boxpix.version keeps MINOR and PATCH under 100, got '$appVersionName'"
    }
    major * 10_000 + minor * 100 + patch
}

// Short SHA for the in-app footer only — never in versionName. providers.exec is
// what keeps the configuration cache valid here; a raw ProcessBuilder would not.
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "nogit"

android {
    namespace = "com.boxpix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.boxpix.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // android.util.Log becomes a no-op in JVM tests (the client logs failures).
        unitTests.isReturnDefaultValues = true
    }
}

// Versioned Room schemas: the committed JSONs are what future migrations are
// written and validated against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websockets)

    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.biometric)

    // XMP write-through (validated by docs/spike-xmp.md)
    implementation(libs.commons.imaging)
    implementation(libs.adobe.xmpcore)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Room is part of the M1 project setup but stays unused until M3 (local index).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
