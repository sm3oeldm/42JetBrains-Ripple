pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases")
    }
}

rootProject.name = "ripple"
