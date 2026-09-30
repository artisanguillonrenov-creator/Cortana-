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
        // JitPack only for the on-device OCR engine (D-20260927-036); no other group may resolve from it.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.adaptech-cz.Tesseract4Android") }
        }
    }
}
rootProject.name = "Cortana"
include(":app")
include(":contracts")
include(":worker")
