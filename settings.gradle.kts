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

rootProject.name = "BassBuzz"
include(":core")
include(":app")
include(":player")
include(":ldaf")
include(":ultrasonic")
include(":watchlauncher")
include(":ioslauncher")
