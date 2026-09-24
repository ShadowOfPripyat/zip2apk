#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Missing $JAR" >&2
  echo "Run ./bootstrap-wrapper.sh once, or run 'gradle wrapper --gradle-version 9.6.0'." >&2
  exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$JAR" "$@"
