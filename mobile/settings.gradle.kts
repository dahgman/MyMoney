// The MyMoney phone app (docs/SYNC-DESIGN.md): shared code (Kotlin Multiplatform,
// for Android now and iOS later) and the Android app around it.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyMoneyMobile"
include(":shared", ":androidApp")
