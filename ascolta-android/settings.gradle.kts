pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's Android runtime (a large file, for the screenshot tests) from Google's
        // mirror of Maven Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Ascolta"
include(":app")
