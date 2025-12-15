import de.undercouch.gradle.tasks.download.Download
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import com.itemis.gradle.spdx.SpdxLicenseMapper
import com.itemis.gradle.spdx.JsonLicenseProvider
import org.gradle.api.publish.maven.tasks.GenerateMavenPom


plugins {
    `maven-publish`
    id("de.undercouch.download") version "5.5.0"
    id("spdx-license-mapping") version "1.0.1-SNAPSHOT"
}

val mpsGroupId = "com.jetbrains.mps"
val mpsArtifactId = "mps-prerelease"

// Third-party license file name
val THIRD_PARTY_LICENSE_FILE = "third-party-libraries.json"

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

val extractThirdPartyLicenses by tasks.registering(Copy::class) {
    val downloadedFile = download.map { it.outputFiles.single() }
    
    from(downloadedFile.map { zipTree(it) }) {
        include("**/$THIRD_PARTY_LICENSE_FILE")
        eachFile {
            // Flatten the directory structure
            path = name
        }
    }
    into(layout.buildDirectory.dir("licenses"))
    includeEmptyDirs = false
    
    doLast {
        val outputFile = destinationDir.resolve(THIRD_PARTY_LICENSE_FILE)
        if (!outputFile.exists()) {
            throw GradleException("Failed to extract $THIRD_PARTY_LICENSE_FILE from downloaded ZIP - file not found in archive")
        }
    }
}

fun getArtifactDownloadUrl(): String {
    val artifactBuildId = getenvRequired("ARTIFACT_BUILD_ID")
    return "https://teamcity.jetbrains.com/guestAuth/app/rest/builds/id:${artifactBuildId}/artifacts/content/MPS-${version}.zip"
}

val repo = publishing.repositories.maven("https://artifacts.itemis.cloud/repository/maven-mps-prereleases") {
    name = "Maven"
    if (project.findProperty("artifacts.itemis.cloud.user") != null) {
        credentials {
            username = project.findProperty("artifacts.itemis.cloud.user") as String?
            password = project.findProperty("artifacts.itemis.cloud.pw") as String?
        }
    }
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
    
    // Use Provider API - Gradle will automatically track the dependency
    artifact(repackage.flatMap { it.archiveFile })
    
    pom {
        withXml {
            val licensesNode = asNode().appendNode("licenses")
            
            // Use the extracted license file from the extractThirdPartyLicenses task
            // The file is accessed from the task's output directory
            val thirdPartyJsonFile = extractThirdPartyLicenses.get().destinationDir.resolve(THIRD_PARTY_LICENSE_FILE)
            
            if (!thirdPartyJsonFile.exists()) {
                throw GradleException("third-party-libraries.json not found at ${thirdPartyJsonFile.absolutePath} - cannot determine licenses")
            }
            
            val licenseProvider = JsonLicenseProvider(thirdPartyJsonFile)
            SpdxLicenseMapper.addLicensesToPom(licensesNode, licenseProvider)
        }
    }
}

fun getenvRequired(name: String) =
    System.getenv(name) ?: throw GradleException("Environment variable '$name' must be set")

// Ensure license extraction happens before POM generation
tasks.withType<GenerateMavenPom>().configureEach {
    dependsOn(extractThirdPartyLicenses)
}

tasks.publish {
    doLast {
        println("##teamcity[buildStatus text='MPS $version successfully published']")
    }
}