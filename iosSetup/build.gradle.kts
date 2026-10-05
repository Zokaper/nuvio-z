import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.nuvio.app.z.setup"
version = "1.1.2"

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

// The Rust device helper (helper/) ships inside the app image as an app resource. It is optional at
// runtime: without it the assistant falls back to manual confirmations. Pass -PwithHelper to build it
// with cargo and package it, or -PwithHelper -PhelperBinary=<path> to package a prebuilt binary
// (CI does this for the macOS universal binary).
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val helperFileName = if (isWindows) "nuvioz-device-helper.exe" else "nuvioz-device-helper"
val helperResourcesDir = layout.buildDirectory.dir("helper-resources")
val helperResourceSubdir = if (isWindows) "windows" else "macos"
val withHelper = providers.gradleProperty("withHelper").isPresent
val prebuiltHelper = providers.gradleProperty("helperBinary")

val buildDeviceHelper by tasks.registering(Exec::class) {
    description = "Builds the Rust device helper with cargo (release, locked dependencies)."
    // Plain values only: the configuration cache cannot serialize references to this script.
    val skip = prebuiltHelper.isPresent
    workingDir = file("helper")
    commandLine("cargo", "build", "--release", "--locked")
    onlyIf { !skip }
}

val stageDeviceHelper by tasks.registering(Copy::class) {
    description = "Copies the device helper into the app resources root."
    val fileName = helperFileName
    if (prebuiltHelper.isPresent) from(file(prebuiltHelper.get())) else {
        dependsOn(buildDeviceHelper)
        from(file("helper/target/release/$fileName"))
    }
    rename { fileName }
    into(helperResourcesDir.map { it.dir(helperResourceSubdir) })
}

if (withHelper) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageDeviceHelper) }
}

compose.desktop {
    application {
        mainClass = "com.nuvio.z.iossetup.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Nuvio Z iOS Setup"
            packageVersion = "1.1.2"
            description = "A guided setup utility for installing Nuvio Z on iPhone with SideStore"
            vendor = "Nuvio Z"
            appResourcesRootDir.set(helperResourcesDir)
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
