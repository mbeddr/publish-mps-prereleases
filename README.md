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
Provide the following custom environment variables to the build:

* `env.ARTIFACT_BUILD_ID` - the build ID from which the artifacts would be taken. It is the last numeric part of the
  build URL, e.g. for `https://teamcity.jetbrains.com/buildConfiguration/MPS_20251_Distribution_DownloadableArtifacts/5562013`
  the build ID would be `5562013`.
* `env.ARTIFACT_VERSION` - the version of the artifact to upload, e.g. `251.28774.615`.
