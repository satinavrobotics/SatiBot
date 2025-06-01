#!/bin/bash

# Set environment variables for increased memory
export GRADLE_OPTS="-Xmx4096m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"

# Run Gradle with the specified task
./gradlew $@ --no-daemon
