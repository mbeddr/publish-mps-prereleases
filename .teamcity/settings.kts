import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.kotlinScript
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.ui.add

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2023.11"

project {
    description = "Publishing MPS pre-releases from teamcity.jetbrains.com to artifacts.itemis.cloud"

    buildType(Publish)
}

object Publish : BuildType({
    name = "Publish"
    description = "Download latest successful MPS build from JetBrains TeamCity and upload to artifacts.itemis.cloud"

    enablePersonalBuilds = false
    type = BuildTypeSettings.Type.DEPLOYMENT
    maxRunningBuilds = 1

    vcs {
        add(DslContext.settingsRoot.id!!)
    }

    params {
        param("env.teamcity_build_branch", "unused")
    }

    steps {
        gradle {
            tasks = ":find-latest-version:run"
        }
        script {
            scriptContent = """
                echo "Detected build ID: ${'$'}ARTIFACT_BUILD_ID"
                echo "Detected version: ${'$'}ARTIFACT_VERSION"
            """.trimIndent()
        }
    }
})
