rootProject.name = "claudiverse-apps"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The shared Kotlin Multiplatform core: the Android app uses its JVM target, the macOS app its native framework.
include(":core")
// The Android app, and the OpenVPN engine it runs its tunnel with.
include(":android")
include(":openvpn")
