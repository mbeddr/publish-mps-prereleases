import org.jetbrains.teamcity.rest.Build
import org.jetbrains.teamcity.rest.BuildArtifact
import org.jetbrains.teamcity.rest.ProjectId
import org.jetbrains.teamcity.rest.TeamCityInstanceFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

const val MPS_GROUP_ID = "com.jetbrains"
const val MPS_ARTIFACT_ID = "mps"

const val REPO_URL = "https://artifacts.itemis.cloud/repository/maven-mps"

val logger: Logger = Logger.getLogger("app")

data class GAV(val group: String, val artifact: String, val version: String) {
    override fun toString(): String = "$group:$artifact:$version"
}

fun extractVersionNumberFromBuild(build: Build): String {
    val buildNumber = build.buildNumber ?: throw IllegalArgumentException("Build does not have a build number: $build")
    return Regex("[0-9.]+").find(buildNumber)?.groups?.get(0)?.value
        ?: throw IllegalArgumentException("Could not extract version number from $buildNumber")
}

private fun findLastSuccessfulBuild(): Build {
    val tc = TeamCityInstanceFactory.guestAuth("https://teamcity.jetbrains.com")
    val mpsToplevelProject = tc.project(ProjectId("MPS"))

    val latestMpsProject = mpsToplevelProject.childProjects.filter { it.id.stringId.startsWith("MPS_20") }
        .maxByOrNull { it.id.stringId }
        ?: throw Exception("No MPS projects found. Found projects: ${mpsToplevelProject.childProjects}")

    logger.fine("Latest project of MPS: $latestMpsProject")

    val projectName = "Distribution"
    val project = latestMpsProject.childProjects.singleOrNull { it.name == projectName }
        ?: throw Exception("Project '${latestMpsProject.name}' does not contain a subproject named '$projectName'")

    logger.fine("Distribution project: $project")

    logger.fine("Build configurations: ${project.buildConfigurations.map { it.id }}")

    val downloadableArtifactsConfig =
        project.buildConfigurations.single { it.id.stringId.endsWith("_Distribution_Binaries") }

    logger.fine("Build configuration: $downloadableArtifactsConfig")

    val lastSuccessfulBuild = tc.builds()
        .fromConfiguration(downloadableArtifactsConfig.id)
        .latest()
        ?: throw Exception("Build configuration ${downloadableArtifactsConfig.id} does not have a successful build")

    logger.fine("Last successful build: $lastSuccessfulBuild")
    return lastSuccessfulBuild
}

fun isArtifactPresentInItemisCloud(gav: GAV): Boolean {
    val url = URL("${REPO_URL}/${gav.group.replace('.', '/')}/${gav.artifact}/${gav.version}/${gav.artifact}-${gav.version}.pom")

    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "HEAD"
        connection.doOutput = false

        return when (val responseCode = connection.responseCode) {
            200 -> true
            404 -> false
            else -> throw RuntimeException("Server returned unexpected response code $responseCode for HEAD $url")
        }
    } finally {
        connection.disconnect()
    }
}

fun main(args: Array<String>) {
    logger.level = Level.INFO

    if (args.size > 1) {
        System.err.println("Usage: find_latest_version [--quiet|--info|--debug]")
        exitProcess(1)
    }

    if (args.size == 1) {
        when (args[0]) {
            "--quiet" -> logger.level = Level.OFF
            "--info" -> logger.level = Level.INFO
            "--debug" -> {
                logger.level = Level.FINE
                Logger.getLogger("").handlers.forEach { it.level = logger.level }
            }
            else -> {
                System.err.println("Unknown argument: $args[0]")
                exitProcess(1)
            }
        }
    }

    logger.info("Looking for latest successful MPS build")
    val build = findLastSuccessfulBuild()

    val artifactVersion = extractVersionNumberFromBuild(build)

    val gav = GAV(MPS_GROUP_ID, MPS_ARTIFACT_ID, artifactVersion)
    logger.info("Looking for $gav in $REPO_URL")

    if (!isArtifactPresentInItemisCloud(gav)) {
        logger.info("Artifact $gav not found in $REPO_URL")
        println("##teamcity[setParameter name='env.ARTIFACT_BUILD_ID' value='${build.id.stringId}']")
        println("##teamcity[setParameter name='env.ARTIFACT_VERSION' value='${gav.version}']")
    } else {
        logger.info("Artifact $gav found in $REPO_URL, nothing to do.")
    }

}
