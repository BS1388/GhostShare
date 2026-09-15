pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Xposed/LSPosed API artifacts
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "GhostShare"
include(":app")
