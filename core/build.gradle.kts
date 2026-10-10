import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/*
 * The claudiverse core: what the Android app and the macOS app share — the
 * server's models, its REST and channel clients, and the rules that turn them
 * into screens (transcript, timeline, seat list).
 *
 * Android consumes the JVM target. The macOS app consumes a native framework
 * (Kotlin/Native, no JVM), built by assembleClaudiverseCoreXCFramework.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.skie)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    val xcframework = XCFramework("ClaudiverseCore")
    macosArm64 {
        binaries.framework {
            baseName = "ClaudiverseCore"
            isStatic = true
            binaryOption("bundleId", "dev.claudiverse.core")
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            // Upload and download stream through kotlinx-io, the I/O Ktor itself uses.
            api(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // Locks that work in common code: the socket is used from the UI thread and its own.
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

skie {
    features {
        group {
            // Kotlin's default arguments in Swift (otherwise every parameter must be passed),
            // and top-level functions as global Swift functions instead of FileNameKt.function.
            co.touchlab.skie.configuration.DefaultArgumentInterop.Enabled(true)
            co.touchlab.skie.configuration.FunctionInterop.FileScopeConversion.Enabled(true)
        }
    }
}
