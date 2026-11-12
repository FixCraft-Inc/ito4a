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

// The minimal build never resolves provider source or its dependencies.
val providersEnabled = providers.gradleProperty("itoProviders").orElse("false").get().let {
    require(it == "true" || it == "false") { "itoProviders must be true or false" }
    it == "true"
}
if (providersEnabled) {
    val providerRoot = providers.gradleProperty("itoProviderRoot").orNull
        ?: error("Enabled providers require -PitoProviderRoot")
    val providerDirectory = file(providerRoot)
    val pinned = providers.gradleProperty("itoProviderLibraryVersion").get()
    require(providerDirectory.resolve("VERSION").readText().trim() == pinned) { "Unsupported provider library version" }
    include(":provider-library")
    project(":provider-library").projectDir = providerDirectory
}
