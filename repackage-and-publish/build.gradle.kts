import de.undercouch.gradle.tasks.download.Download
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*
import groovy.json.JsonSlurper
import com.itemis.gradle.spdx.SpdxLicenseMapper
import com.itemis.gradle.spdx.JsonLicenseProvider
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository


plugins {
    `maven-publish`
    id("de.undercouch.download") version "5.5.0"
    id("spdx-license-mapping") version "1.0.1"
}

val mpsGroupId = "com.jetbrains.mps"
val mpsArtifactId = "mps-prerelease"
val jbrArtifactId = "mps-jbr"
val repositoryName = "maven-mps-prereleases"

// Third-party license file name
val THIRD_PARTY_LICENSE_FILE = "third-party-libraries.json"

version = object {
    override fun toString(): String {
        return getenvRequired("ARTIFACT_VERSION")
    }
}

tasks.register("checkAlreadyPublished") {
    doLast {
        val prereleasePublished = doesArtifactExistInRepository(mpsArtifactId)
        val jbrPublished = doesArtifactExistInRepository(jbrArtifactId)
        val alreadyPublished = prereleasePublished && jbrPublished
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

val extractBuildProperties by tasks.registering(Copy::class) {
    val downloadedFile = download.map { it.outputFiles.single() }

    from(downloadedFile.map { zipTree(it) }) {
        // Only the distribution's top-level build.properties (a single path segment deep); the archive also contains
        // build.properties files under plugins/, which must not be matched.
        include("*/build.properties")
        eachFile {
            // Flatten the directory structure
            path = name
        }
    }
    into(layout.buildDirectory.dir("build-properties"))
    includeEmptyDirs = false

    doLast {
        val outputFile = destinationDir.resolve("build.properties")
        if (!outputFile.exists()) {
            throw GradleException("Failed to extract build.properties from downloaded ZIP - file not found in archive")
        }
    }
}

fun getArtifactDownloadUrl(): String {
    val artifactBuildId = getenvRequired("ARTIFACT_BUILD_ID")
    return "https://teamcity.jetbrains.com/guestAuth/app/rest/builds/id:${artifactBuildId}/artifacts/content/MPS-${version}.zip"
}

val repo = publishing.repositories.maven("https://artifacts.itemis.cloud/repository/$repositoryName") {
    name = "Maven"
    if (project.findProperty("artifacts.itemis.cloud.user") != null) {
        credentials {
            username = project.findProperty("artifacts.itemis.cloud.user") as String?
            password = project.findProperty("artifacts.itemis.cloud.pw") as String?
        }
    }
}

fun <T> repositoryRequest(url: URL, method: String, readResponse: (HttpURLConnection) -> T): T {
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    connection.instanceFollowRedirects = false
    val credentials = repo.credentials
    if (!credentials.username.isNullOrEmpty() && !credentials.password.isNullOrEmpty()) {
        val basicHeader = "Basic " + Base64.getEncoder()
            .encodeToString("${credentials.username}:${credentials.password}".toByteArray())
        connection.setRequestProperty("Authorization", basicHeader)
    }

    try {
        return readResponse(connection)
    } finally {
        connection.disconnect()
    }
}

fun doesArtifactExistInRepository(artifactId: String, extension: String = "pom"): Boolean {
    val url = URL("${repo.url}/${mpsGroupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.$extension")
    logger.info("Checking URL $url")
    return repositoryRequest(url, "HEAD") { connection ->
        when (val responseCode = connection.responseCode) {
            200 -> true
            404 -> false
            else -> throw GradleException("Server returned unexpected response code $responseCode for HEAD $url")
        }
    }
}

fun prereleaseComponentIds(): List<String> {
    val componentIds = mutableListOf<String>()
    var continuationToken: String? = null
    do {
        val parameters = linkedMapOf(
            "repository" to repositoryName,
            "maven.groupId" to mpsGroupId,
            "maven.artifactId" to mpsArtifactId,
            "maven.baseVersion" to version.toString(),
        )
        continuationToken?.let { parameters["continuationToken"] = it }
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val url = repo.url.resolve("/service/rest/v1/search?$query").toURL()
        val response = repositoryRequest(url, "GET") { connection ->
            if (connection.responseCode != 200) {
                throw GradleException("Server returned HTTP ${connection.responseCode} when searching for incomplete MPS $version")
            }
            connection.inputStream.bufferedReader().use { JsonSlurper().parse(it) as Map<*, *> }
        }
        val items = response["items"] as List<*>
        for (item in items) {
            val component = item as Map<*, *>
            // Search results must match the exact coordinates before they can be deleted.
            if (component["repository"] == repositoryName && component["group"] == mpsGroupId &&
                component["name"] == mpsArtifactId && component["version"] == version.toString()) {
                componentIds.add(component["id"] as String)
            }
        }
        continuationToken = response["continuationToken"] as String?
    } while (continuationToken != null)
    return componentIds
}

fun deleteIncompletePrerelease() {
    // Keep a publication whose POM appeared after the publish task's existence check.
    if (doesArtifactExistInRepository(mpsArtifactId)) {
        throw GradleException("MPS $version POM appeared before cleanup; rerun to skip the completed publication")
    }
    for (componentId in prereleaseComponentIds()) {
        logger.lifecycle("Deleting incomplete $mpsGroupId:$mpsArtifactId:$version (including its assets and checksums)")
        val url = repo.url.resolve("/service/rest/v1/components/${URLEncoder.encode(componentId, "UTF-8")}").toURL()
        repositoryRequest(url, "DELETE") { connection ->
            when (val responseCode = connection.responseCode) {
                204, 404 -> Unit
                403 -> throw GradleException("Cannot delete incomplete MPS $version: the publishing account needs delete permission (HTTP 403)")
                else -> throw GradleException("Server returned HTTP $responseCode when deleting incomplete MPS $version")
            }
        }
    }
    if (prereleaseComponentIds().isNotEmpty() || doesArtifactExistInRepository(mpsArtifactId) ||
        doesArtifactExistInRepository(mpsArtifactId, "zip")) {
        throw GradleException("Incomplete MPS $version still exists after cleanup; refusing to publish")
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

// Publish a marker POM declaring the JetBrains Runtime (JBR) that this MPS version is built with.
//
// The runtime build is read from build.properties (mps.runtimeBuild) and transformed into the version format published
// as com.jetbrains.jdk:jbr_jcef: a '-' is inserted before the build number, e.g. '17.0.11b1207.24' becomes
// '17.0.11-b1207.24'.
//
// The marker is published under the same coordinates as for MPS releases (com.jetbrains.mps:mps-jbr), with the
// prerelease build-number version, into the maven-mps-prereleases repository.
fun jbrJcefVersion(): String {
    val buildPropertiesFile = extractBuildProperties.get().destinationDir.resolve("build.properties")
    if (!buildPropertiesFile.exists()) {
        throw GradleException("build.properties not found at ${buildPropertiesFile.absolutePath} - cannot determine JBR runtime version")
    }

    val properties = Properties()
    buildPropertiesFile.inputStream().use { properties.load(it) }

    val runtimeBuild = properties.getProperty("mps.runtimeBuild")
        ?: throw GradleException("mps.runtimeBuild property not found in ${buildPropertiesFile.absolutePath}")

    val matcher = Regex("""^(\d+(?:\.\d+)*)b(.+)$""").matchEntire(runtimeBuild)
        ?: throw GradleException("Unexpected mps.runtimeBuild format '$runtimeBuild', expected e.g. '17.0.11b1207.24'")

    return "${matcher.groupValues[1]}-b${matcher.groupValues[2]}"
}

val jbrPublication = publishing.publications.create<MavenPublication>("mpsJbr") {
    groupId = mpsGroupId
    artifactId = jbrArtifactId

    pom {
        packaging = "pom"
        withXml {
            val dependencyNode = asNode().appendNode("dependencies").appendNode("dependency")
            dependencyNode.appendNode("groupId", "com.jetbrains.jdk")
            dependencyNode.appendNode("artifactId", "jbr_jcef")
            dependencyNode.appendNode("version", jbrJcefVersion())
        }
    }
}

fun getenvRequired(name: String) =
    System.getenv(name) ?: throw GradleException("Environment variable '$name' must be set")

// Each POM reads a file extracted from the distribution: the mps-prerelease POM embeds the third-party licenses, and
// the mps-jbr POM reads the JBR runtime version from build.properties. Wire each POM to only the extraction it needs.
tasks.named<GenerateMavenPom>("generatePomFileForMpsPrereleasePublication") {
    dependsOn(extractThirdPartyLicenses)
}
tasks.named<GenerateMavenPom>("generatePomFileForMpsJbrPublication") {
    dependsOn(extractBuildProperties)
}

tasks.named<PublishToMavenRepository>("publishMpsPrereleasePublicationToMavenRepository") {
    onlyIf("the prerelease POM is missing") { !doesArtifactExistInRepository(mpsArtifactId) }
    // Publishing task dependencies prepare the ZIP and POM before cleanup runs.
    doFirst { deleteIncompletePrerelease() }
}
tasks.named<PublishToMavenRepository>("publishMpsJbrPublicationToMavenRepository") {
    onlyIf("the JBR POM is missing") { !doesArtifactExistInRepository(jbrArtifactId) }
}

tasks.publish {
    doLast {
        println("##teamcity[buildStatus text='MPS $version successfully published']")
    }
}
