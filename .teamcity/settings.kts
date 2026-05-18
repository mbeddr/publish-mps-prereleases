import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.GradleBuildStep
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.triggers.schedule
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
    type = Type.DEPLOYMENT
    maxRunningBuilds = 1

    vcs {
        add(DslContext.settingsRoot.id!!)
    }

    params {
        param("env.teamcity_build_branch", "unused")
        text(
            "env.ARTIFACT_BUILD_URL",
            "",
            display = ParameterDisplay.PROMPT,
            label = "Build URL",
            description = "Optional: URL of a build on teamcity.jetbrains.com, e.g. https://teamcity.jetbrains.com/buildConfiguration/MPS_20251_Distribution_DownloadableArtifacts/6131521. Leave empty to auto-detect the latest build.",
            allowEmpty = true,
        )
    }

    steps {
        gradle {
            name = "Find build information"
            tasks = ":find-build-info:run"
        }
        gradle {
            tasks = ":repackage-and-publish:checkAlreadyPublished"
        }
        gradle {
            conditions {
                doesNotEqual("alreadyPublished", "true")
            }
            tasks = ":repackage-and-publish:publish"
        }

        // Set the JDK for all Gradle steps to JDK 17-x64
        items.filterIsInstance<GradleBuildStep>().forEach { it.jdkHome = "%env.JDK_17_0_x64%" }
    }

    triggers {
        schedule {
            schedulingPolicy = cron {
                seconds = "0"
                minutes = "0"
                hours = "*/3"
                dayOfMonth = "?"
                month = "*"
                dayOfWeek = "*"
                year = "*"
            }
            triggerBuild = always()
            withPendingChangesOnly = false
        }
    }
})
