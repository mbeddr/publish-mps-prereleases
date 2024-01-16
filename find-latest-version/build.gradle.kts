group = "com.specificlanguages.sync-tools"
version = "1.0"

plugins {
    application
    kotlin("jvm") version "1.9.20"
}

repositories {
    maven("https://packages.jetbrains.team/maven/p/teamcity-rest-client/teamcity-rest-client")
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.teamcity:teamcity-rest-client:1.21.0")

    // teamcity-rest-client 1.21.0 erroneously omits transitive dependencies so we have to include them explicitly.
    runtimeOnly("com.squareup.retrofit:retrofit:1.9.0")
    runtimeOnly("com.squareup.okhttp3:okhttp:4.7.2")
    runtimeOnly("com.jakewharton.retrofit:retrofit1-okhttp3-client:1.1.0")

    runtimeOnly("org.slf4j:slf4j-jdk14:1.7.+")

    val mavenResolverVersion = "1.9.18"

    implementation("org.apache.maven.resolver:maven-resolver-supplier")
    implementation("org.apache.maven.resolver:maven-resolver-util")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")

    implementation(platform("org.apache.maven.resolver:maven-resolver:${mavenResolverVersion}"))

}

tasks.run.configure {
    mainClass = "Find_latest_versionKt"
    args("--info")
}
