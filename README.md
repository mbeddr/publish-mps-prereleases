Publishing of MPS prereleases (nightly builds) from [JetBrains TeamCity](https://teamcity.jetbrains.com)
to [itemis Nexus](https://artifacts.itemis.cloud).

Subprojects:

* [find-latest-version](find-latest-version): Kotlin application that uses TeamCity REST API to look up the latest MPS
  project on TeamCity and the latest successful build from the Binaries configuration within.

* [repackage-and-publish](repackage-and-publish): Gradle script to download an MPS artifact from a TeamCity build,
  repackage it to strip the topmost directory and publish it to a repository.
