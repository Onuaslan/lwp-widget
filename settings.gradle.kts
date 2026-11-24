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
rootProject.name = "AquareaWidget"
include(":api")
// Das Android-Modul braucht ein Android SDK. Ohne SDK (z.B. reiner JVM-Test) kann man es mit
// -PwithoutApp=true ausklammern.
if (providers.gradleProperty("withoutApp").orNull != "true") {
    include(":app")
}
