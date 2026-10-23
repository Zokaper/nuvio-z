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
        versionCode = 7
        versionName = "install-proof-0.5.2"
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
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) {
    it.sources.jniLibs?.addGeneratedSourceDirectory(buildUsbProbeRust, BuildUsbProbeRust::outputDirectory)
}

dependencies {
    // Matches rustls-platform-verifier-android 0.2.0 in this experiment's Cargo.lock.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
}
