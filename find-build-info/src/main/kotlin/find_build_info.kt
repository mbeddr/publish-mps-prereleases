import org.jetbrains.teamcity.rest.Build
import org.jetbrains.teamcity.rest.BuildId
import org.jetbrains.teamcity.rest.ProjectId
import org.jetbrains.teamcity.rest.TeamCityInstanceFactory
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

val logger: Logger = Logger.getLogger("app")

fun extractVersionNumberFromBuild(build: Build): String {
    val buildNumber = build.buildNumber ?: throw IllegalArgumentException("Build does not have a build number: $build")
    return Regex("[0-9.]+").find(buildNumber)?.value
        ?: throw IllegalArgumentException("Could not extract version number from build number '$buildNumber'")
}

fun parseBuildIdFromUrl(url: String): String {
    // Expected format: https://teamcity.jetbrains.com/buildConfiguration/<configId>/<buildId>
    val match = Regex("/([0-9]+)$").find(url.trimEnd('/'))
        ?: throw IllegalArgumentException(
            "Could not extract build ID from URL: $url\n" +
                "Expected format: https://teamcity.jetbrains.com/buildConfiguration/<configId>/<buildId>"
        )
    return match.groupValues[1]
}

private fun findBuildByUrl(buildUrl: String): Build {
    val buildId = parseBuildIdFromUrl(buildUrl)
    logger.info("Extracted build ID: $buildId")

    val tc = TeamCityInstanceFactory.guestAuth("https://teamcity.jetbrains.com")
    val build = tc.build(BuildId(buildId))

    logger.info("Found build: ${build.buildNumber} (status: ${build.status})")

    if (build.status?.name != "SUCCESS") {
        logger.warning("Build $buildId has status '${build.status}' (not SUCCESS)")
    }

    return build
}

private fun findLastSuccessfulBuild(): Build {
    val tc = TeamCityInstanceFactory.guestAuth("https://teamcity.jetbrains.com")
    val mpsToplevelProject = tc.project(ProjectId("MPS"))

    val mps20Regex = Regex("20\\d\\d\\.\\d")
    val latestMpsProject = mpsToplevelProject.childProjects.filter { it.id.stringId.startsWith("MPS_20") && it.name.matches(mps20Regex) }
        .maxByOrNull { it.id.stringId }
        ?: throw Exception("No MPS projects found. Found projects: ${mpsToplevelProject.childProjects}")

    logger.info("Latest project of MPS: $latestMpsProject")

    val projectName = "Distribution"
    val project = latestMpsProject.childProjects.singleOrNull { it.name == projectName }
        ?: throw Exception("Project '${latestMpsProject.name}' does not contain a subproject named '$projectName'")

    logger.info("Distribution project: $project")

    logger.fine("Build configurations: ${project.buildConfigurations.map { it.id }}")

    val downloadableArtifactsConfig =
        project.buildConfigurations.single { it.id.stringId.endsWith("_Distribution_Binaries") }

    logger.info("Build configuration: $downloadableArtifactsConfig")

    val lastSuccessfulBuild = tc.builds()
        .fromConfiguration(downloadableArtifactsConfig.id)
        .latest()
        ?: throw Exception("Build configuration ${downloadableArtifactsConfig.id} does not have a successful build")

    logger.info("Last successful build: $lastSuccessfulBuild")
    return lastSuccessfulBuild
}

fun main(args: Array<String>) {
    logger.level = Level.INFO

    if (args.size == 1) {
        when (args[0]) {
            "--quiet" -> logger.level = Level.OFF
            "--info" -> logger.level = Level.INFO
            "--debug" -> {
                logger.level = Level.FINE
                Logger.getLogger("").handlers.forEach { it.level = logger.level }
            }
            else -> {
                System.err.println("Unknown argument: ${args[0]}")
                exitProcess(1)
            }
        }
    }

    val buildUrl: String? = System.getenv("ARTIFACT_BUILD_URL")

    val build = if (!buildUrl.isNullOrBlank()) {
        logger.info("Resolving build from URL: $buildUrl")
        findBuildByUrl(buildUrl)
    } else {
        logger.info("Looking for latest successful MPS build")
        findLastSuccessfulBuild()
    }

    val artifactVersion = extractVersionNumberFromBuild(build)

    println("##teamcity[setParameter name='env.ARTIFACT_BUILD_ID' value='${build.id.stringId}']")
    println("##teamcity[setParameter name='env.ARTIFACT_VERSION' value='$artifactVersion']")
    println("##teamcity[buildStatus text='MPS $artifactVersion (build ${build.id.stringId})']")
}
