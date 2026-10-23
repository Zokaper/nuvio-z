import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.androidApplication)
}
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://raw.githubusercontent.com/rustls/rustls-platform-verifier/maven-archive/android-release-support/maven/")
        content { includeGroup("org.rustls") }
    }
}

android {
    namespace = "com.nuvio.usbprobe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()
    defaultConfig {
        applicationId = "com.nuvio.app.z.usbprobe"
        minSdk = 24
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 8
        versionName = "installer-0.6.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// This experiment has no release variant, even when explicitly included in the build.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }

abstract class BuildUsbProbeRust @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSources: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScript: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun build() {
        execOperations.exec {
            commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                buildScript.get().asFile.absolutePath, "-Arm64Only", "-OutputDirectory", outputDirectory.get().asFile.absolutePath)
        }
    }
}
val buildUsbProbeRust = tasks.register<BuildUsbProbeRust>("buildUsbProbeRust") {
    nativeSources.from(fileTree(projectDir.resolve("../native")) { exclude("build/**", "target/**") })
    buildScript.set(projectDir.resolve("../build-native.ps1"))
    outputDirectory.set(layout.buildDirectory.dir("generated/jniLibs"))
}
// The installer's screens are desktop's own iosInstaller/ui, copied at build time (never a second copy in git),
// with android-bridge.js loaded before the shared scripts so they find window.__TAURI__ as on desktop.
abstract class CopyInstallerUi : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val ui: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val bridge: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun copy() {
        val out = outputDirectory.get().asFile.resolve("ui")
        out.deleteRecursively()
        ui.get().asFile.copyRecursively(out)
        bridge.get().asFile.copyTo(out.resolve("android-bridge.js"))
        val index = out.resolve("index.html")
        val marker = "<script src=\"icons.js\"></script>"
        val html = index.readText()
        check(html.contains(marker)) { "ui/index.html no longer loads icons.js first; update CopyInstallerUi" }
        index.writeText(html.replace(marker, "<script src=\"android-bridge.js\"></script>\n  $marker"))
    }
}
val copyInstallerUi = tasks.register<CopyInstallerUi>("copyInstallerUi") {
    ui.set(projectDir.resolve("../../ui"))
    bridge.set(projectDir.resolve("src/main/web/android-bridge.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/installerUi"))
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) {
    it.sources.jniLibs?.addGeneratedSourceDirectory(buildUsbProbeRust, BuildUsbProbeRust::outputDirectory)
    it.sources.assets?.addGeneratedSourceDirectory(copyInstallerUi, CopyInstallerUi::outputDirectory)
}

dependencies {
    // Matches rustls-platform-verifier-android 0.2.0 in this experiment's Cargo.lock.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
}
