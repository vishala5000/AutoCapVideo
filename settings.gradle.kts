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
        // REQUIRED: JitPack is needed to fetch sherpa-onnx Android AAR
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AutoCapVideo"
include(":app")
