#!/bin/sh
set -eu
mkdir -p gradle/wrapper
curl -fL "https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar
printf '%s  %s\n' "$(curl -fsL https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar.sha256)" "gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -
echo "Gradle wrapper ready."
