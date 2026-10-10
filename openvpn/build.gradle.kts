/*
 * The OpenVPN 2 engine of ics-openvpn (OpenVPN for Android) by Arne Schwabe,
 * built as a library for the Claudiverse Android app.
 * Distributed under the GNU GPL v2 with additional terms; see LICENSE.txt.
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 36
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = 21
        // The engine's single switch between its OpenVPN 2 and 3 back ends; only 2 is built.
        buildConfigField("boolean", "openvpn3", "false")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            // CMake copies the pie_openvpn binaries here.
            assets.srcDirs("src/main/assets", "build/ovpnassets")
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }
}

// CMake writes the pie binaries into build/ovpnassets, which the assets merge
// reads; without this the merge can run first and package none of them.
val mergeAssets = Regex("merge(Debug|Release)Assets")
tasks.configureEach {
    val buildType = mergeAssets.matchEntire(name)?.groupValues?.get(1) ?: return@configureEach
    dependsOn("externalNativeBuild$buildType")
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
}
