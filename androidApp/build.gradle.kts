import com.android.build.api.variant.ResValue
import java.util.Base64
import java.util.Properties

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sentry.android.gradle)
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = localProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = localProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = localProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = localProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val unsignedReleaseRequested = providers.gradleProperty("nuvio.android.unsignedRelease")
    .map(String::toBooleanStrict)
    .getOrElse(false)
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = !unsignedReleaseRequested && releaseSigningValues.all { it != null }
if (!unsignedReleaseRequested && releaseSigningValues.any { it != null } && !hasReleaseSigning) {
    error("Android release signing is partially configured; provide all NUVIO_RELEASE_* signing properties or none")
}
fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryOrg = envOrLocalProperty("SENTRY_ORG")
val sentryProject = envOrLocalProperty("SENTRY_PROJECT")
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = providers.gradleProperty("nuvio.app.versionName").orNull
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
// The debug update channel needs its own monotonic version, because every debug APK cut from
// one release version would otherwise look identical to the installed one and no update would
// ever be offered. Version name gains a fourth component; version code is derived so it always
// rises with the release line it was cut from. Debug is a separate applicationId, so this
// numbering cannot collide with the release line.
// Its own file, and it must stay that way: debug and stable build ordering are independent.
val appDebugVersionConfigFile = rootProject.file("iosApp/Configuration/DebugVersion.xcconfig")
val debugAppBuildNumber = readXcconfigValue(appDebugVersionConfigFile, "DEBUG_BUILD")
    ?.toIntOrNull()
    ?: 1
val debugAppVersionName = "$releaseAppVersionName.$debugAppBuildNumber"
val debugAppVersionCode = releaseAppVersionCode * 1000 + debugAppBuildNumber
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val buildsReleaseApks = requestedTaskNames.any {
    it.startsWith("assemble", ignoreCase = true) && it.endsWith("Release", ignoreCase = true)
}

android {
    namespace = "com.nuvio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    signingConfigs {
        // Committed on purpose (see .gitignore). Every debug APK must carry one signature or the
        // in-app debug update line cannot install over an existing debug install - Android
        // rejects a signature change and the only way out is an uninstall. AGP's per-machine
        // default debug key cannot give that.
        create("debugShared") {
            storeFile = rootProject.file("androidApp/nuvio-debug.keystore")
            storePassword = "nuviodebug"
            keyAlias = "nuviodebug"
            keyPassword = "nuviodebug"
        }
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app.z"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // The Firebase resources below are res values; AGP 9 no longer generates them by default.
        resValues = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets.getByName("full") {
        manifest.srcFile("src/full/AndroidManifest.xml")
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    androidResources {
        noCompress += "cvr"
    }

    splits {
        abi {
            isEnable = buildsReleaseApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugShared")
            versionNameSuffix = ".$debugAppBuildNumber"
        }
        getByName("release") {
            val minifyRelease = providers.gradleProperty("releaseMinifyEnabled")
                .map(String::toBooleanStrict)
                .getOrElse(true)
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
            // Build-only CI intentionally supports an unsigned release artifact when production
            // credentials are unavailable. Publication verifies a complete signing configuration
            // and refuses unsigned APKs before creating a GitHub release.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Firebase, for Social push (2026-10-18). The Google Services plugin is deliberately not applied: it
// fails the build without google-services.json, and the repository is public, so the file is never
// committed. Instead the same resources the plugin would generate (google_app_id and friends) are added
// per variant from `androidApp/google-services.json` (gitignored) or NUVIO_GOOGLE_SERVICES_JSON (CI,
// raw JSON or base64). Firebase then initialises itself; with neither present it does not, and the app
// simply has no system notifications for Social.
class FirebaseClientConfig(val appId: String, val apiKey: String)

val googleServicesJsonText: String? = project.file("google-services.json").takeIf { it.isFile }?.readText()
    ?: System.getenv("NUVIO_GOOGLE_SERVICES_JSON")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        if (raw.startsWith("{")) raw else String(Base64.getMimeDecoder().decode(raw), Charsets.UTF_8)
    }

val googleServicesRoot: Map<*, *>? = googleServicesJsonText?.let { groovy.json.JsonSlurper().parseText(it) as Map<*, *> }
// project id to sender id (the project number).
val firebaseProject: Pair<String, String>? = (googleServicesRoot?.get("project_info") as? Map<*, *>)
    ?.let { (it["project_id"] as String) to (it["project_number"] as String) }
val firebaseClients: Map<String, FirebaseClientConfig> =
    (googleServicesRoot?.get("client") as? List<*>).orEmpty().mapNotNull { entry ->
        val client = entry as Map<*, *>
        val clientInfo = client["client_info"] as Map<*, *>
        val packageName = (clientInfo["android_client_info"] as Map<*, *>)["package_name"] as String
        val apiKey = ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"] as String
        packageName to FirebaseClientConfig(clientInfo["mobilesdk_app_id"] as String, apiKey)
    }.toMap()

androidComponents {
    onVariants { variant ->
        val project = firebaseProject ?: return@onVariants
        // The debug id is set by the block below; resolve it the same way rather than read it back.
        val packageName = if (variant.buildType == "debug") "com.nuvio.app.z.debug" else "com.nuvio.app.z"
        val client = firebaseClients[packageName] ?: return@onVariants
        fun put(name: String, value: String) {
            variant.resValues.put(variant.makeResValueKey("string", name), ResValue(value, "google-services.json"))
        }
        put("google_app_id", client.appId)
        put("google_api_key", client.apiKey)
        put("google_crash_reporting_api_key", client.apiKey)
        put("gcm_defaultSenderId", project.second)
        put("project_id", project.first)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.nuvio.app.z.debug")
        // Must rise with every published debug build: Android refuses to install an APK whose
        // versionCode is below the installed one, which would read as "the update is broken".
        variant.outputs.forEach { output -> output.versionCode.set(debugAppVersionCode) }
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

// The Sentry plugin still wires an upload task when auto-upload is disabled.
// Avoid invoking sentry-cli (which also mishandles Windows paths with spaces)
// unless a complete upload configuration is actually present.
tasks.matching { it.name.startsWith("uploadSentry", ignoreCase = true) }.configureEach {
    enabled = sentryMappingUploadEnabled
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")
}
