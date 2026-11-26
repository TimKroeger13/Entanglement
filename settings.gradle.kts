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
include(":shared")
// ":desktopApp" and ":androidApp" follow in the next milestones.
// The server is written in Go (see server/) and not part of the Gradle build.
