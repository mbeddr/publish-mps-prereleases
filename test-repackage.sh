#!/bin/bash

# Test script for repackage-and-publish without uploading to Maven repository
# This script publishes to the local Maven repository (~/.m2/repository)

set -e  # Exit on error

# Set test environment variables
export ARTIFACT_VERSION="${ARTIFACT_VERSION:-253.28294.10118}"
export ARTIFACT_BUILD_ID="${ARTIFACT_BUILD_ID:-5736255}"

echo "=================================="
echo "Testing repackage-and-publish"
echo "=================================="
echo "ARTIFACT_VERSION: $ARTIFACT_VERSION"
echo "ARTIFACT_BUILD_ID: $ARTIFACT_BUILD_ID"
echo ""

# Run complete publication to local Maven repository
echo "Running complete publication to local Maven repository..."
./gradlew :repackage-and-publish:publishToMavenLocal --refresh-dependencies --info

echo ""
echo "=================================="
echo "Build completed successfully!"
echo "=================================="
echo ""
echo "Generated artifacts:"
echo "  - Repackaged ZIP: repackage-and-publish/build/repackage/"
echo "  - Extracted licenses: repackage-and-publish/build/extracted-licenses.txt"
echo "  - Generated POM: repackage-and-publish/build/publications/mpsPrerelease/pom-default.xml"
echo "  - Local Maven: ~/.m2/repository/com/jetbrains/mps/mps-prerelease/$ARTIFACT_VERSION/"
echo ""
echo "To view the POM file:"
echo "  cat repackage-and-publish/build/publications/mpsPrerelease/pom-default.xml"
echo ""
echo "To view the published POM in local Maven:"
echo "  cat ~/.m2/repository/com/jetbrains/mps/mps-prerelease/$ARTIFACT_VERSION/mps-prerelease-$ARTIFACT_VERSION.pom"
