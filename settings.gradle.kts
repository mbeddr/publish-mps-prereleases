pluginManagement {
    repositories {
        maven("https://artifacts.itemis.cloud/repository/gradle-plugins/") 
        gradlePluginPortal()
    }
}

rootProject.name = "publish-mps-prereleases"

include(":find-latest-version")
include(":repackage-and-publish")
