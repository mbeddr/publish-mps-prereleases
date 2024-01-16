import de.undercouch.gradle.tasks.download.Download

plugins {
    `maven-publish`
    id("de.undercouch.download") version "5.5.0"
}

version = object  {
    override fun toString(): String {
        return getenvRequired("ARTIFACT_VERSION")
    }
}

val download by tasks.registering(Download::class) {
    src(::getArtifactUrl)
    dest(layout.buildDirectory.dir("download"))

    overwrite(false)
}

val repackage by tasks.registering(Zip::class) {
    val downloadedFile = download.map { it.outputFiles.single() }
    from(zipTree(downloadedFile))
    archiveFileName = downloadedFile.map { it.name.substringBeforeLast('.') + "-repackaged.zip" }
    destinationDirectory = layout.buildDirectory.dir("repackage")

    eachFile {
        this.path = this.sourcePath.substringAfter('/')
    }

    includeEmptyDirs = false
}

fun getArtifactUrl(): String {
    val artifactBuildId = getenvRequired("ARTIFACT_BUILD_ID")
    return "https://teamcity.jetbrains.com/guestAuth/app/rest/builds/id:${artifactBuildId}/artifacts/content/MPS-${version}.zip"
}

publishing {
    publications {
        register<MavenPublication>("mpsPrerelease") {
            groupId = "com.jetbrains.mps"
            artifactId = "mps-prerelease"

            artifact(repackage)
        }
    }
}

fun getenvRequired(name: String) = System.getenv(name) ?: throw GradleException("Environment variable '$name' must be set")
