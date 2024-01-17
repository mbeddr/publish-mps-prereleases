import org.jetbrains.teamcity.rest.Build
import org.jetbrains.teamcity.rest.ProjectId
import org.jetbrains.teamcity.rest.TeamCityInstanceFactory
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

val logger: Logger = Logger.getLogger("app")

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

    println("##teamcity[setParameter name='env.ARTIFACT_BUILD_ID' value='${build.id.stringId}']")
    println("##teamcity[setParameter name='env.ARTIFACT_VERSION' value='${artifactVersion}']")
    println("##teamcity[buildStatus text='Latest MPS prerelease build is ${artifactVersion}']")
}
