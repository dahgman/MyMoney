plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.martinsmayhem.mymoney.mobile.shared"
        compileSdk = 37
        minSdk = 26
    }
    jvm()          // the sync code's tests run on the computer

    applyDefaultHierarchyTemplate()
    sourceSets {
        // What Android and the JVM share: javax.crypto, java.util.zip, files.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // Part of the shared code's own API (Phone, LedgerView, WebDavMailbox use them), so the app sees them too.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        jvmSharedMain.dependencies {
            api(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The end-to-end test (DesktopInteropTest) syncs with the real desktop code, when it's been
        // built (mvn package in the folder above): the desktop's jar and its libraries.
        val desktopJar = rootProject.file("../target/MyMoney.jar")
        if (desktopJar.exists()) {
            jvmTest.dependencies {
                implementation(files(desktopJar))
                implementation(fileTree(rootProject.file("../target/lib")) { include("*.jar") })
            }
        }
    }
}
