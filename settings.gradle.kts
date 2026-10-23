rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":androidApp")
include(":iosSetup")

// Explicitly opted-in, debug-only USB experiment; absent from ordinary app/release builds.
if (providers.gradleProperty("nuvio.androidIosUsbProbe").orNull == "true") {
    include(":androidUsbProbe")
    project(":androidUsbProbe").projectDir = file("iosInstaller/android-probe/app")
}
