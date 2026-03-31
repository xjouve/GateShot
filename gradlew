#!/bin/sh
# Gradle wrapper script — delegates to the downloaded Gradle distribution
# Auto-generated for GateShot

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DIRNAME=$(dirname "$0")
CLASSPATH="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"

warn () { echo "$*"; }
die () { echo "$*"; exit 1; }

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
