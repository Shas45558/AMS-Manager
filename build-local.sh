#!/usr/bin/env bash
set -e
chmod +x ./gradlew
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Gradle wrapper JAR missing; generating it with installed Gradle..."
  gradle wrapper --gradle-version 8.9
fi
./gradlew assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
