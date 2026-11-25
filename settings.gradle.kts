pluginManagement {
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        // Chaquopy's plugin adds its runtime/target artifacts through the
        // buildscript classpath, which resolves from these repositories.
        maven { url = uri("https://chaquo.com/maven") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        // Chaquopy runtime artifacts (Stage 3 Python) live on Chaquo's repo.
        maven { url = uri("https://chaquo.com/maven") }
    }
}
rootProject.name = "Miftah"
include(":app")
