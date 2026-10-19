#!/usr/bin/env bash
#
# Builds the MyMoney phone app (Android) with the same version as the desktop
# (the <version> in pom.xml), and copies it to dist/mymoney-android-<version>.apk.
#
#   packaging/package-android.sh
#
# Needs: the Android SDK (mobile/local.properties says where) and a JDK 26
# (JAVA_HOME), the same one as the desktop build. See mobile/README.md.
#
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(sed -n 's:.*<artifactId>MyMoney</artifactId>.*::;/<version>/{s:.*<version>\(.*\)</version>.*:\1:p;q}' pom.xml)"
echo "== MyMoney $VERSION: building the Android app"

(cd mobile && ./gradlew --console=plain -q :androidApp:assembleDebug)

mkdir -p dist
cp mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk "dist/mymoney-android-$VERSION.apk"
echo "== Done: dist/mymoney-android-$VERSION.apk"
