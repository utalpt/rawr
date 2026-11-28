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

rootProject.name = "RawrCam"
include(":app")
// Rawr 17U fork: the Wear OS companion (Watch Remote).
include(":wear")
// Headless GPU test APK: its preBuild needs a full release native build, so opt in with -Prawr.gpuTests=true.
if (providers.gradleProperty("rawr.gpuTests").orNull == "true") include(":gpu-tests")
