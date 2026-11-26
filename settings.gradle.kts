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
        // NewPipeExtractor and InnerTubeX are published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Oto"
include(":shared")
include(":desktopUi")
include(":desktopApp")
