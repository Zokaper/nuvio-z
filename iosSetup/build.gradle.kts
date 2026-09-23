import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.nuvio.app.z.setup"
version = "1.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.nuvio.z.iossetup.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Nuvio Z iOS Setup"
            packageVersion = "1.1.0"
            description = "A guided setup utility for installing Nuvio Z on iPhone with SideStore"
            vendor = "Nuvio Z"
            modules("java.net.http", "java.naming", "java.management")
            windows {
                menuGroup = "Nuvio Z"
                shortcut = false
                perUserInstall = true
                dirChooser = false
            }
            macOS {
                bundleID = "com.nuvio.app.z.iossetup"
            }
        }
    }
}

tasks.test {
    useJUnit()
}
