Publishing of MPS pre-releases (nightly builds) from [JetBrains TeamCity](https://teamcity.jetbrains.com)
to [itemis Nexus](https://artifacts.itemis.cloud).

Subprojects:

* [find-latest-version](find-latest-version): Kotlin application that uses TeamCity REST API to look up the latest MPS
  project on TeamCity and the latest successful build from the Binaries configuration within.

* [repackage-and-publish](repackage-and-publish): Gradle script to download an MPS artifact from a TeamCity build,
  repackage it to strip the topmost directory and publish it to a repository.

Usage:

Regular scheduled builds upload the latest available version.

To upload a specific version, run a custom build of the corresponding configuration on the itemis TeamCity.
You will be prompted to provide the following custom environment variable to the build:

* `env.ARTIFACT_BUILD_URL` - the TeamCity build URL from which the artifacts should be taken, e.g.
  `https://teamcity.jetbrains.com/buildConfiguration/MPS_20251_Distribution_DownloadableArtifacts/5562013`.
  Leave it empty to fetch the latest build.

The workflow extracts the build ID from the URL and derives the artifact version from the TeamCity build number.
