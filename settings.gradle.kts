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

rootProject.name = "Entanglement"
include(":shared", ":server")

// The Docker build passes -PserverOnly so it needs neither the Android SDK nor Compose.
if (!providers.gradleProperty("serverOnly").isPresent) {
    // include(":desktopApp", ":androidApp") – added in the next milestones
}
