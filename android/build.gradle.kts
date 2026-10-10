/*
 * The Claudiverse Android app. It links the OpenVPN engine (:openvpn), so it
 * is distributed under the GNU GPL v2 as well; see LICENSE.txt.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** The output of a git command in this repository, or null outside one or before the first commit. */
fun git(vararg args: String): String? =
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }

// A real, rising version so the app can tell a newer build (self-update): the
// commit count above a base that keeps it ahead of the builds made before
// this repository, and the commit itself for display and comparison.
val versionBase = providers.gradleProperty("claudiverse.versionBase").get().toInt()
val commits = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val commit = git("rev-parse", "--short=8", "HEAD") ?: "unknown"

android {
    namespace = "dev.claudiverse.app"
    compileSdk = 36
    // Strips the engine's native libraries when packaging.
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        applicationId = "dev.claudiverse.app"
        minSdk = 21
        targetSdk = 36
        versionCode = versionBase + commits
        versionName = "1.0.${versionBase + commits}"
        buildConfigField("String", "GIT_COMMIT", "\"$commit\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            // From ~/.gradle/gradle.properties, never from this repository.
            val keystoreFile: String? by project
            storeFile = keystoreFile?.let { file(it) }
            val keystorePassword: String? by project
            storePassword = keystorePassword
            val keystoreAliasPassword: String? by project
            keyPassword = keystoreAliasPassword
            val keystoreAlias: String? by project
            keyAlias = keystoreAlias
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // The engine runs libovpnexec.so as an executable, so it must be
            // extracted to disk rather than mapped from the APK.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":openvpn"))

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlin)
    implementation(libs.square.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
}
