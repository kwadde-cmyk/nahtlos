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
        google()
        mavenCentral()
    }
}

rootProject.name = "weiterhoeren"

include(":core-model")
include(":server")

// Android modules stay out of the server image: the Docker build only copies
// core-model and server, so these files are absent there.
if (file("mobile/build.gradle.kts").exists()) {
    include(":core-android")
    include(":mobile")
    include(":automotive")
}
