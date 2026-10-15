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
        // Uncomment ONLY if Gradle fails to resolve com.startapp:inapp-sdk
        // from mavenCentral() — older Start.io SDK versions were published
        // to their own repo instead. See the comment above that dependency
        // in app/build.gradle.kts.
        // maven { url = uri("https://sdk-android-mvn.startappnetwork.com/reposit") }
    }
}
rootProject.name = "Lenspilot"
include(":app")
