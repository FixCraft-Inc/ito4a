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

rootProject.name = "Ito"
include(":app")

// Independent provider sources; neither application is a dependency of the other.
val providerRoot = providers.gradleProperty("itoProviderRoot").orNull
    ?: error("Set -PitoProviderRoot to the qualified yume4a/provider-library checkout")
val providerDirectory = file(providerRoot)
require(providerDirectory.resolve("VERSION").readText().trim() == "0.1.0") { "Unsupported provider library version" }
include(":provider-library")
project(":provider-library").projectDir = providerDirectory
