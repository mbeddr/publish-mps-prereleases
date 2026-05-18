pluginManagement {
    repositories {
        maven("https://artifacts.itemis.cloud/repository/gradle-plugins/") 
        gradlePluginPortal()
    }
}

rootProject.name = "publish-mps-prereleases"

include(":find-build-info")
include(":repackage-and-publish")
