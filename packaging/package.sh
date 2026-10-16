#!/usr/bin/env bash
#
# Builds the MyMoney installer for the system this runs on (Linux or macOS),
# with its own trimmed-down Java runtime inside, so users don't need Java.
# (Windows: use packaging\package.ps1.)  See docs/PACKAGING.md for the why.
#
#   packaging/package.sh              Linux: a .deb      macOS: a .dmg
#   packaging/package.sh rpm          Linux (Fedora and friends): a .rpm
#   packaging/package.sh pkg          macOS: a .pkg installer
#   packaging/package.sh app-image    any: just the runnable app folder (quick to test, nothing installed)
#
# Needs: a JDK 26 (JAVA_HOME, or its bin on the PATH) and Maven (mvn on the
# PATH, or MVN=/path/to/mvn). On Linux, a .deb also needs dpkg-deb and
# fakeroot; a .rpm needs rpm-build.
#
# The installer lands in dist/.

set -euo pipefail
cd "$(dirname "$0")/.."                            # the project folder

# ----- Tools -----------------------------------------------------------------
if [[ -n "${JAVA_HOME:-}" ]]; then
    BIN="$JAVA_HOME/bin/"
elif command -v jpackage >/dev/null; then
    BIN=""
elif [[ -x /usr/lib/apache-netbeans/jdk/bin/jpackage ]]; then
    BIN="/usr/lib/apache-netbeans/jdk/bin/"           # the JDK that comes with NetBeans
    export JAVA_HOME=/usr/lib/apache-netbeans/jdk
else
    echo "No JDK found: set JAVA_HOME to a JDK 26." >&2
    exit 1
fi
if [[ -z "${MVN:-}" ]]; then
    if command -v mvn >/dev/null; then
        MVN=mvn
    elif [[ -x /usr/lib/apache-netbeans/java/maven/bin/mvn ]]; then
        MVN=/usr/lib/apache-netbeans/java/maven/bin/mvn  # the Maven that comes with NetBeans
    else
        echo "No Maven found: put mvn on the PATH, or set MVN." >&2
        exit 1
    fi
fi

# ----- What we're building ---------------------------------------------------
OS="$(uname -s)"
case "$OS" in
    Linux)  TYPE="${1:-deb}" ;;
    Darwin) TYPE="${1:-dmg}" ;;
    *)      echo "On Windows, use packaging\\package.ps1." >&2; exit 1 ;;
esac
# The project's version (the first <version> in pom.xml), as installers want it: numbers only.
VERSION="$(sed -n 's|^ *<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)"
APP_VERSION="${VERSION%%-*}"                       # 1.0.0-SNAPSHOT -> 1.0.0
echo "== MyMoney $VERSION: building a $TYPE for $OS"

# ----- 1. Compile, test and build the jar (target/MyMoney.jar, target/lib/) ----
"$MVN" -q -B clean package

# ----- 2. Gather what goes in the app: the jar and its libraries -------------
# The work happens in a local temporary folder: jlink makes symbolic links, which
# some drives (network shares, some USB drives) can't hold. Only the finished
# installer is put in dist/.
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/mymoney-build.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/input" dist
cp target/MyMoney.jar "$BUILD/input/"
cp -r target/lib "$BUILD/input/"

# ----- 3. A Java runtime with just the modules MyMoney uses ------------------
MODULES="$("${BIN}jdeps" --multi-release 26 --ignore-missing-deps --print-module-deps \
            --class-path "target/lib/*" target/MyMoney.jar)"
echo "== Java modules needed: $MODULES"
"${BIN}jlink" --add-modules "$MODULES" \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output "$BUILD/runtime"

# ----- 4. Package it --------------------------------------------------------
COMMON=(
    --name MyMoney
    --app-version "$APP_VERSION"
    --vendor "Martin's Mayhem"
    --description "MyMoney: personal finance"
    --copyright "Copyright $(date +%Y) Mike Martin"
    --input "$BUILD/input"
    --main-jar MyMoney.jar
    --main-class com.martinsmayhem.mymoney.MyMoney
    --runtime-image "$BUILD/runtime"
    --java-options "--enable-native-access=ALL-UNNAMED"
    --dest "$BUILD/out"
    --temp "$BUILD/jpackage"                       # (kept local too)
)
case "$OS" in
    Linux)
        PLATFORM=(
            --icon packaging/icons/MyMoney.png
            --resource-dir packaging/linux          # our own .desktop file (see there)
        )
        if [[ "$TYPE" != "app-image" ]]; then
            PLATFORM+=(
                --linux-package-name mymoney
                --linux-shortcut                    # a menu / launcher entry
                --linux-menu-group "Office;Finance"
                --linux-app-category misc
            )
        fi
        ;;
    Darwin)
        PLATFORM=(
            --icon packaging/icons/MyMoney.icns
            --mac-package-identifier com.martinsmayhem.mymoney
            --mac-package-name MyMoney
        )
        ;;
esac
"${BIN}jpackage" --type "$TYPE" "${COMMON[@]}" "${PLATFORM[@]}"

rm -rf "dist/MyMoney"                              # (an older app-image, if any)
cp -rL "$BUILD/out/." dist/                        # -L: copies of any symbolic links (see step 2)
echo "== Done:"
ls -la dist/
