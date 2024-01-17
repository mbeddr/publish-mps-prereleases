import de.undercouch.gradle.tasks.download.Download
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

plugins {
    `maven-publish`
    id("de.undercouch.download") version "5.5.0"
}

val mpsGroupId = "com.jetbrains.mps"
val mpsArtifactId = "mps-prerelease"

version = object {
    override fun toString(): String {
        return getenvRequired("ARTIFACT_VERSION")
    }
}

tasks.register("checkAlreadyPublished") {
    doLast {
        val alreadyPublished = doesArtifactExistInRepository()
        println("##teamcity[setParameter name='alreadyPublished' value='$alreadyPublished']")

        val status = if (alreadyPublished)
            "MPS $version already exists in the repository"
        else
            "MPS $version will be uploaded to the repository"

        println("##teamcity[buildStatus text='$status']")
    }
}

val download by tasks.registering(Download::class) {
    src(::getArtifactDownloadUrl)
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

fun getArtifactDownloadUrl(): String {
    val artifactBuildId = getenvRequired("ARTIFACT_BUILD_ID")
    return "https://teamcity.jetbrains.com/guestAuth/app/rest/builds/id:${artifactBuildId}/artifacts/content/MPS-${version}.zip"
}

fun doesArtifactExistInRepository(): Boolean {
    val url =
        URL("${repo.url}/${mpsGroupId.replace('.', '/')}/${mpsArtifactId}/${version}/${mpsArtifactId}-${version}.pom")
    logger.info("Checking URL $url")

    val connection = url.openConnection() as HttpURLConnection
    val credentials = repo.credentials
    if (!credentials.username.isNullOrEmpty() && !credentials.password.isNullOrEmpty()) {
        logger.info("Using Basic authentication with username ${credentials.username}")
        val basicHeader = "Basic " + Base64.getEncoder()
            .encodeToString("${credentials.username}:${credentials.password}".toByteArray())
        connection.setRequestProperty("Authorization", basicHeader)
    }

    try {
        connection.requestMethod = "HEAD"
        connection.doOutput = false

        val responseCode = connection.responseCode
        logger.info("Received HTTP response code $responseCode")

        return when (responseCode) {
            200 -> true
            404 -> false
            else -> throw RuntimeException("Server returned unexpected response code $responseCode for HEAD $url")
        }
    } finally {
        connection.disconnect()
    }
}

val prereleasePublication = publishing.publications.create<MavenPublication>("mpsPrerelease") {
    groupId = mpsGroupId
    artifactId = mpsArtifactId

    artifact(repackage)
}

val repo = publishing.repositories.maven("https://maven.pkg.github.com/mbeddr/publish-mps-prereleases") {
    if (project.findProperty("gpr.user") != null) {
        credentials {
            username = project.findProperty("gpr.user") as String?
            password = project.findProperty("gpr.token") as String?
        }
    }
}

fun getenvRequired(name: String) =
    System.getenv(name) ?: throw GradleException("Environment variable '$name' must be set")

tasks.publish {
    doLast {
        println("##teamcity[buildStatus text='MPS $version successfully published']")
    }
}
