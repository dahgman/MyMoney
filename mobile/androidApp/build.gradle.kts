plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The app's version is the desktop's (the <version> in ../pom.xml), so the two always match.
// Android's versionCode, which must go up with every release, is worked out from it: 1.1.1 -> 10101.
val desktopVersion: String = Regex("<artifactId>MyMoney</artifactId>\\s*<version>([^<]+)</version>")
    .find(rootProject.file("../pom.xml").readText())?.groupValues?.get(1)?.trim()
    ?: error("No <version> found in ../pom.xml")
val desktopVersionCode: Int = desktopVersion.split('.', '-').take(3)
    .map { part -> part.takeWhile { it.isDigit() }.ifEmpty { "0" }.toInt() }
    .let { it + List(3 - it.size) { 0 } }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "com.martinsmayhem.mymoney.mobile.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.martinsmayhem.mymoney"
        minSdk = 26
        targetSdk = 37
        versionCode = desktopVersionCode
        versionName = desktopVersion
    }
    buildFeatures {
        compose = true
        buildConfig = true                   // BuildConfig.VERSION_NAME, shown on the Sync screen
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.embedded)
}
