# Packaging MyMoney: from source code to an installer

This guide walks through turning the MyMoney project into an installer you can
hand to someone: a `.deb` for Ubuntu/Debian, an `.rpm` for Fedora, an `.msi` for
Windows, or a `.dmg` for macOS. The person installing it **doesn't need Java**:
the installer carries its own small copy.

There's a script that does all of it in one go (`packaging/package.sh`, or
`packaging\package.ps1` on Windows), but the point of this guide is to
understand each step, so it first does everything **by hand**, one tool at a
time, explaining what each one does and how to check it worked. Once you've
seen the pieces, the script is just those same commands in a row.

---

## Contents

1. [The big picture](#1-the-big-picture)
2. [What you need](#2-what-you-need)
3. [The quick way: one command](#3-the-quick-way-one-command)
4. [Step by step, by hand](#4-step-by-step-by-hand)
   - [Step 1: Set the version and release date](#step-1-set-the-version-and-release-date)
   - [Step 2: Build a runnable jar (Maven)](#step-2-build-a-runnable-jar-maven)
   - [Step 3: Find the Java modules it uses (jdeps)](#step-3-find-the-java-modules-it-uses-jdeps)
   - [Step 4: Build a small Java runtime (jlink)](#step-4-build-a-small-java-runtime-jlink)
   - [Step 5: Make the app folder (jpackage app-image)](#step-5-make-the-app-folder-jpackage-app-image)
   - [Step 6: Make the installer (jpackage)](#step-6-make-the-installer-jpackage)
   - [Step 7: Install it and test it](#step-7-install-it-and-test-it)
5. [Each platform's details](#5-each-platforms-details)
6. [Releasing a new version](#6-releasing-a-new-version)
7. [Signing (before giving it to other people)](#7-signing-before-giving-it-to-other-people)
8. [Troubleshooting](#8-troubleshooting)
9. [What each packaging file does](#9-what-each-packaging-file-does)

---

## 1. The big picture

A Java program is compiled to `.class` files, which need a Java runtime (a JVM)
to run. Packaging means bundling your program **and** a runtime into something
that installs and launches like any other application.

```
  src/main/java/...          your source code
        │
        │  Maven (mvn package): compiles, runs the tests, builds the jar
        ▼
  target/MyMoney.jar         your program
  target/lib/*.jar           the libraries it uses (sqlite-jdbc)
        │
        │  jdeps: which parts of Java does the program use?
        ▼
  "java.base,java.desktop,java.sql"
        │
        │  jlink: build a Java runtime with only those parts
        ▼
  runtime/                   ~60 MB instead of the full JDK's ~310 MB
        │
        │  jpackage: program + runtime + launcher + icon → installer
        ▼
  dist/mymoney_1.0.0_amd64.deb      (Linux)
  dist/MyMoney-1.0.0.msi            (Windows)
  dist/MyMoney-1.0.0.dmg            (macOS)
```

All three tools (**jdeps**, **jlink**, **jpackage**) come with the JDK, in its
`bin` folder. Nothing else needs downloading, except a little per platform
(section 2).

**One rule to remember: jpackage only builds installers for the system it's
running on.** A `.deb` needs Linux, an `.msi` needs Windows, a `.dmg` needs a
Mac. To offer all three, you build on all three (or use a CI service such as
GitHub Actions, which has Linux, Windows and Mac machines).

---

## 2. What you need

| | All platforms | Linux | Windows | macOS |
|---|---|---|---|---|
| **JDK 26** | ✔ (the NetBeans one is fine: `/usr/lib/apache-netbeans/jdk`) | | | |
| **Maven** | ✔ (NetBeans has one: `/usr/lib/apache-netbeans/java/maven/bin/mvn`) | | | |
| Extra | | `.deb`: `dpkg-deb` and `fakeroot` (already on Ubuntu). `.rpm`: `sudo apt install rpm` (or Fedora's `rpm-build`) | **WiX Toolset** ([wixtoolset.org](https://wixtoolset.org)) on the PATH, for `.msi`/`.exe` | Xcode command-line tools: `xcode-select --install` |

Check the JDK has the tools (Linux/macOS shown; on Windows use `%JAVA_HOME%\bin`):

```bash
export JAVA_HOME=/usr/lib/apache-netbeans/jdk
$JAVA_HOME/bin/java -version        # should say 26
ls $JAVA_HOME/bin | grep -E 'jdeps|jlink|jpackage'
```

In the commands below, `$JAVA_HOME/bin/` is written out so it's clear where each
tool comes from. If the JDK's `bin` is on your PATH, you can leave it off.

---

## 3. The quick way: one command

From the project folder:

```bash
packaging/package.sh                 # Linux → dist/mymoney_1.0.0_amd64.deb   (macOS → .dmg)
packaging/package.sh rpm             # Linux → a .rpm
packaging/package.sh app-image       # any → dist/MyMoney/, the app folder, nothing installed (for testing)
```

On Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-26"     # wherever your JDK 26 is
powershell -ExecutionPolicy Bypass -File packaging\package.ps1          # → dist\MyMoney-1.0.0.msi
```

The script finds the NetBeans JDK and Maven by itself if `JAVA_HOME` and `mvn`
aren't set. A build takes about a minute, most of it the tests.

The rest of this guide is what that script does, step by step.

---

## 4. Step by step, by hand

Run these from the project folder (`/mnt/thehive-home/GitRepos/MyMoney`).

> **A note about where you do this.** jlink creates *symbolic links* inside the
> runtime it builds. Some drives can't store those: network shares (like this
> project's `/mnt/thehive-home`), and USB sticks formatted FAT32 or exFAT. On
> those, step 4 fails with `Operation not supported`. So do the work in a local
> folder; this guide uses `~/mymoney-build`. (The script uses a temporary
> folder for the same reason, and only copies the finished installer back.)

```bash
export JAVA_HOME=/usr/lib/apache-netbeans/jdk
export MVN=/usr/lib/apache-netbeans/java/maven/bin/mvn      # or just "mvn" if it's on your PATH
export WORK=~/mymoney-build
rm -rf "$WORK" && mkdir -p "$WORK"
```

### Step 1: Set the version and release date

The version lives in **one** place: `pom.xml`, near the top:

```xml
<version>1.0.0</version>
```

And its release date, shown in Help > About, in the `<properties>` further down:

```xml
<release.date>2026-10-16</release.date>
```

Use three numbers, `MAJOR.MINOR.PATCH` (this is "semantic versioning"):

- **PATCH** (1.0.**1**): bug fixes only.
- **MINOR** (1.**1**.0): new features; everything that worked still works.
- **MAJOR** (**2**.0.0): big changes, like a data file the old version can't open.

Installers need plain numbers. A `-SNAPSHOT` suffix (Maven's word for "work in
progress") is stripped off by the script.

**How the app knows them:** the file
`src/main/resources/com/martinsmayhem/mymoney/version.properties` contains
`version=${project.version}` and `releaseDate=${release.date}`. The `<resources>`
section in `pom.xml` tells Maven to *filter* that one file while building,
replacing each `${...}` with the pom's value. The app reads it at startup: the
splash screen shows the version, and Help > About both. Running from NetBeans
without a Maven build, they show "dev" and "Development build".

### Step 2: Build a runnable jar (Maven)

```bash
$MVN clean package
```

- `clean` deletes `target/`, so nothing stale is left over.
- `package` compiles, runs all the tests (the build stops if any fail), and
  builds the jar.

You'll get:

```
target/MyMoney.jar                    your program (~850 KB)
target/lib/sqlite-jdbc-3.53.4.0.jar   the database library (~16 MB)
```

Two plugins in `pom.xml` make this jar *runnable*:

- **maven-jar-plugin** writes a *manifest*, `META-INF/MANIFEST.MF` inside the
  jar, saying which class to start and where the libraries are. Look at it:

  ```bash
  unzip -p target/MyMoney.jar META-INF/MANIFEST.MF
  ```
  ```
  Main-Class: com.martinsmayhem.mymoney.MyMoney
  Class-Path: lib/sqlite-jdbc-3.53.4.0.jar
  Enable-Native-Access: ALL-UNNAMED
  ```
  `Enable-Native-Access` tells Java it's fine for SQLite to load native code
  (SQLCipher, for the encryption). Without it, Java prints a warning.

- **maven-dependency-plugin** copies the libraries into `target/lib`, where the
  manifest's `Class-Path` expects them.

**Check:** run it with your JDK. It should start normally:

```bash
$JAVA_HOME/bin/java -jar target/MyMoney.jar
```

This works on any machine *that has Java 26*. The remaining steps take that
requirement away.

Copy the program into the work folder for the next steps:

```bash
mkdir -p "$WORK/input"
cp target/MyMoney.jar "$WORK/input/"
cp -r target/lib "$WORK/input/"
```

### Step 3: Find the Java modules it uses (jdeps)

Since Java 9, the JDK is split into about 70 *modules*: `java.base` (the core),
`java.desktop` (Swing, AWT, images), `java.sql` (JDBC), `java.net.http`, and so
on. A program only needs the ones it actually uses. **jdeps** reads the compiled
classes and lists them:

```bash
$JAVA_HOME/bin/jdeps --multi-release 26 --ignore-missing-deps --print-module-deps \
    --class-path "target/lib/*" target/MyMoney.jar
```
```
java.base,java.desktop,java.sql
```

- `--class-path "target/lib/*"`: look at the libraries too (sqlite-jdbc).
- `--print-module-deps`: print just the list, comma-separated, ready for jlink.
- `--multi-release 26`: some jars hold different classes for different Java
  versions; look at the Java 26 ones.
- `--ignore-missing-deps`: sqlite-jdbc *can* use optional extras (like a logging
  library) that aren't here. They're optional, so don't stop over them.

So MyMoney needs just three modules. If a future change uses something new (say
`java.net.http` to download bank data), jdeps will list it. That's why the
script runs jdeps every time rather than hard-coding the list.

### Step 4: Build a small Java runtime (jlink)

**jlink** builds a Java runtime containing only the listed modules:

```bash
$JAVA_HOME/bin/jlink \
    --add-modules java.base,java.desktop,java.sql \
    --strip-debug --no-man-pages --no-header-files \
    --compress=zip-6 \
    --output "$WORK/runtime"
```

| Option | What it does |
|---|---|
| `--add-modules` | The modules from step 3 (and whatever they need in turn) |
| `--strip-debug` | Leaves out debugging information (smaller; stack traces still show class and method names) |
| `--no-man-pages`, `--no-header-files` | Leaves out documentation and C headers no user needs |
| `--compress=zip-6` | Compresses the class files inside the runtime |
| `--output` | Where to put it. Must not exist yet. |

**Check:** compare the sizes, then run MyMoney on its own runtime:

```bash
du -sh "$JAVA_HOME" "$WORK/runtime"     # about 310 MB vs about 60 MB
"$WORK/runtime/bin/java" -jar "$WORK/input/MyMoney.jar"
```

If it starts, the runtime has everything it needs. If a module were missing,
you'd see an error like `NoClassDefFoundError: java/sql/...`, and you'd add that
module to `--add-modules`.

### Step 5: Make the app folder (jpackage app-image)

**jpackage** puts it all together. First, just the *app image*: the folder that
an installer would put on the user's computer. It's quick to build and needs no
installing, so it's the best way to test.

```bash
$JAVA_HOME/bin/jpackage --type app-image \
    --name MyMoney \
    --app-version 1.0.0 \
    --vendor "Martin's Mayhem" \
    --input "$WORK/input" \
    --main-jar MyMoney.jar \
    --main-class com.martinsmayhem.mymoney.MyMoney \
    --runtime-image "$WORK/runtime" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --icon packaging/icons/MyMoney.png \
    --dest "$WORK/out"
```

| Option | What it does |
|---|---|
| `--type app-image` | Just the app folder (no installer) |
| `--name` | The app's name: the launcher, the folder, the menu entry |
| `--app-version` | Its version (numbers only) |
| `--vendor` | Who makes it (shown by installers) |
| `--input` | The folder with the jar and its `lib/`. **Everything** in it goes in the app. |
| `--main-jar`, `--main-class` | What to start |
| `--runtime-image` | The runtime from step 4. (Without this, jpackage runs jlink itself with *every* module: a much bigger app.) |
| `--java-options` | Options for the JVM, like the native-access one (the launcher doesn't read the jar's manifest for this) |
| `--icon` | The icon: `.png` on Linux, `.ico` on Windows, `.icns` on macOS |
| `--dest` | Where to put the result |

Look at what it made:

```
$WORK/out/MyMoney/
├── bin/MyMoney                 the launcher (a small native program)
└── lib/
    ├── MyMoney.png             the icon
    ├── app/
    │   ├── MyMoney.jar         your program
    │   ├── lib/sqlite-jdbc…    its library
    │   └── MyMoney.cfg         the launcher's settings: class path, main class, Java options
    └── runtime/                the Java runtime from step 4
```

(On Windows it's `MyMoney\MyMoney.exe` with `app\` and `runtime\` beside it. On
macOS it's `MyMoney.app`.)

**Check:** run the launcher. This is exactly how the installed app starts:

```bash
"$WORK/out/MyMoney/bin/MyMoney"
```

`MyMoney.cfg` is worth reading: it's how the launcher knows what to run.

```bash
cat "$WORK/out/MyMoney/lib/app/MyMoney.cfg"
```

### Step 6: Make the installer (jpackage)

The same command with an installer `--type`, plus options for that platform.
On Linux:

```bash
$JAVA_HOME/bin/jpackage --type deb \
    --name MyMoney \
    --app-version 1.0.0 \
    --vendor "Martin's Mayhem" \
    --description "MyMoney: personal finance" \
    --copyright "Copyright 2026 Mike Martin" \
    --input "$WORK/input" \
    --main-jar MyMoney.jar \
    --main-class com.martinsmayhem.mymoney.MyMoney \
    --runtime-image "$WORK/runtime" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --icon packaging/icons/MyMoney.png \
    --resource-dir packaging/linux \
    --linux-package-name mymoney \
    --linux-shortcut \
    --linux-menu-group "Office;Finance" \
    --linux-app-category misc \
    --dest "$WORK/out"
```

The Linux-only options:

| Option | What it does |
|---|---|
| `--linux-package-name mymoney` | The package's name for `apt` (lower case, by convention) |
| `--linux-shortcut` | Adds MyMoney to the applications menu |
| `--linux-menu-group` | Which menu categories it appears under |
| `--linux-app-category` | The package's section (`misc`, `office`, …) |
| `--resource-dir packaging/linux` | Our own versions of files jpackage would otherwise generate (see below) |

You'll get `$WORK/out/mymoney_1.0.0_amd64.deb`: the package name, version and
processor type (`amd64` is 64-bit Intel/AMD).

**Look inside without installing it:**

```bash
dpkg-deb -I "$WORK/out/mymoney_1.0.0_amd64.deb"      # the package's details, including what it Depends on
dpkg-deb -c "$WORK/out/mymoney_1.0.0_amd64.deb"      # every file, and where it will go (/opt/mymoney/...)
```

**About `--resource-dir` and the `.desktop` file.** On Linux, the applications
menu is made from `.desktop` files. jpackage writes one, but it leaves out a line
GNOME needs to match the running window to the menu entry: `StartupWMClass`.
Without it, the dock shows a generic Java icon for the running app, separate from
MyMoney's own. So `packaging/linux/MyMoney.desktop` is our own copy, with that
line added. jpackage looks in `--resource-dir` for a file named
`<name>.desktop`, and uses it in place of its own. Words like
`APPLICATION_LAUNCHER` in it are placeholders that jpackage fills in.

How do you find the right `StartupWMClass`? Run the app, then run `xprop` and
click its window. The `WM_CLASS` line is the answer. For a Java app it's the
main class with dots turned into dashes:
`com-martinsmayhem-mymoney-MyMoney`.

### Step 7: Install it and test it

```bash
sudo apt install "$WORK/out/mymoney_1.0.0_amd64.deb"
```

(Use `apt install ./file.deb`, not `dpkg -i`: apt also installs anything listed
under *Depends*.)

What happens:

- The app goes in `/opt/mymoney/`.
- MyMoney appears in the applications menu, with its icon.
- The dock shows the MyMoney icon while it runs.

Check that:

1. It starts from the menu, with the splash screen showing the right version.
2. It opens your file, and everything works.
3. **Your data isn't touched:** data files stay wherever you saved them, and
   preferences stay in `~/.config/MyMoney/`. Installing, upgrading and removing
   the app never changes either.

Uninstall:

```bash
sudo apt remove mymoney
```

Installing a newer version's `.deb` simply replaces the old one.

---

## 5. Each platform's details

### Linux

- **`.deb` or `.rpm`.** `.deb` is for Debian, Ubuntu, Mint and Pop!_OS. `.rpm`
  is for Fedora, RHEL and openSUSE. Build each on that kind of system:
  `packaging/package.sh rpm` (needs `rpm` installed).
- **Build on the oldest system you want to support.** jpackage works out the
  *Depends* list from the machine it runs on. Built on Ubuntu 24.04, the package
  asks for `libasound2t64`, a library name that older releases (Ubuntu 22.04,
  Debian 12) call `libasound2`. So a `.deb` built here installs on Ubuntu 24.04
  and newer.
- **Maintainer.** `dpkg-deb -I` shows `Maintainer: Martin's Mayhem <Unknown>`.
  To fill it in, add `--linux-deb-maintainer you@example.com` to the
  `PLATFORM=(...)` list in `packaging/package.sh`.

### Windows

Run `packaging\package.ps1` on a Windows machine.

- **WiX Toolset** builds the `.msi` / `.exe`. Install it, make sure it's on the
  PATH, then open a new PowerShell. jpackage looks for it there.
- **`.msi` or `.exe`.** `.msi` is the standard Windows installer: IT departments
  can deploy it, and it repairs and uninstalls cleanly. `.exe` wraps the same
  thing in a single runnable file. Either is fine for personal use.
- The Windows-only options in the script:

| Option | What it does |
|---|---|
| `--win-menu`, `--win-menu-group MyMoney` | A Start menu entry, in a MyMoney folder |
| `--win-shortcut`, `--win-shortcut-prompt` | A desktop shortcut, asking first |
| `--win-dir-chooser` | Lets the user choose where to install |
| `--win-upgrade-uuid` | **Never change this.** Windows uses it to recognise "this is MyMoney". With it, installing 1.1.0 *replaces* 1.0.0 instead of putting a second copy beside it. |

- It installs into `C:\Program Files\MyMoney\`, and is uninstalled from
  Settings > Apps.
- Preferences live in `%APPDATA%\MyMoney\`.
- The icon is `packaging/icons/MyMoney.ico`, which holds several sizes in one
  file (16 to 256 pixels).

### macOS

Run `packaging/package.sh` on a Mac. It makes a `.dmg` (a disk image the user
drags MyMoney from into Applications) or a `.pkg` with `package.sh pkg`.

- `--mac-package-identifier com.martinsmayhem.mymoney` is the app's unique ID,
  like Windows' upgrade UUID. Keep it the same forever.
- The icon is `packaging/icons/MyMoney.icns`.
- **Unsigned apps are blocked by Gatekeeper** on other people's Macs (see
  section 7). On your own Mac, right-click the app and choose **Open** the first
  time.

---

## 6. Releasing a new version

1. **Commit your work** (everything you want in the release).
2. **Set the version and release date** in `pom.xml`: for example `1.0.0` → `1.1.0`,
   and `<release.date>` to the day you release it.
3. **Build** with `packaging/package.sh`. It runs the tests first; fix anything
   that fails.
4. **Test** the installer: install it over the previous version, and check that
   your data opens and the splash screen shows the new version.
5. **Commit the version change and tag it,** so you can always get back to
   exactly what you released:
   ```bash
   git commit -am "Release 1.1.0"
   git tag v1.1.0
   ```
6. **Build on the other platforms** from that same tag:
   `git checkout v1.1.0`, then run the script there.
7. **Keep the installers somewhere outside the project** (`dist/` is ignored by
   git on purpose), or attach them to a GitHub release.

**Data file versions.** MyMoney upgrades a data file when a newer version opens
it (the schema version in `Database.java`). After that, older versions refuse to
open it. Users can't go back to an older MyMoney once a newer one has opened
their file, so test upgrades on a *copy* of a real data file before you release.

---

## 7. Signing (before giving it to other people)

Operating systems warn about, or block, apps from publishers they can't verify.
For your own use, you can click past the warning. Before giving MyMoney to other
people, sign it:

| Platform | Without signing | To sign |
|---|---|---|
| **Windows** | SmartScreen: "Windows protected your PC… unknown publisher" | Buy a code-signing certificate (from Sectigo, DigiCert or similar, or use Azure Trusted Signing). Then sign the installer: `signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a dist\MyMoney-1.1.0.msi` |
| **macOS** | Gatekeeper: "MyMoney can't be opened because Apple cannot check it" | Join the Apple Developer Program. Add `--mac-sign --mac-signing-key-user-name "Developer ID Application: Your Name (TEAMID)"` to jpackage. Then *notarize*: `xcrun notarytool submit dist/MyMoney-1.1.0.dmg --keychain-profile <profile> --wait`, then `xcrun stapler staple dist/MyMoney-1.1.0.dmg` |
| **Linux** | No warning for a `.deb` installed with `apt install ./file.deb` | Only needed if you set up your own apt repository (then you sign the repository with GPG) |

---

## 8. Troubleshooting

| Problem | Why | Fix |
|---|---|---|
| `jlink … FileSystemException … Operation not supported` | jlink makes symbolic links; network shares and FAT/exFAT drives can't hold them | Build in a local folder (the script does this for you) |
| `cp: cannot create symbolic link … Operation not supported` | The same, copying the app image onto such a drive | `cp -rL` copies the files the links point to (the script does this) |
| The installed app doesn't start | Something failed before the window appeared | Run the launcher from a terminal to see the error: `/opt/mymoney/bin/MyMoney` (Windows: `"C:\Program Files\MyMoney\MyMoney.exe"`) |
| `NoClassDefFoundError: java/…` | A Java module is missing from the runtime | Run jdeps again (step 3). If the class is loaded by name at runtime, jdeps can't see it; add its module to `--add-modules` yourself |
| A warning about `restricted method … System::load` | Native access not enabled | Keep `--java-options "--enable-native-access=ALL-UNNAMED"` |
| Generic Java icon in the dock (Linux) | The `.desktop` file's `StartupWMClass` doesn't match the window | Check with `xprop` (step 6) |
| Splash says "Version dev" | Running from classes Maven didn't build (version.properties wasn't filtered) | Build with Maven; installers always are |
| `jpackage` on Windows: "Can not find WiX tools" | WiX isn't installed or isn't on the PATH | Install WiX, then open a new PowerShell window |
| `apt` complains about dependencies on an older Ubuntu/Debian | Built on a newer system (see section 5) | Build on the oldest system you want to support |

---

## 9. What each packaging file does

| File | What it's for |
|---|---|
| `pom.xml` | The version and release date; `finalName` (the jar is always `MyMoney.jar`); the jar plugin (Main-Class, Class-Path, native access); the dependency plugin (copies libraries to `target/lib`); filtering for `version.properties` |
| `src/main/resources/com/martinsmayhem/mymoney/version.properties` | Gets the pom's version and release date at build time; the splash screen and Help > About show them |
| `packaging/package.sh` | Builds the Linux or macOS installer: steps 2–6 in one go |
| `packaging/package.ps1` | The same for Windows |
| `packaging/icons/MyMoney.png` / `.ico` / `.icns` | The installer and launcher icons for Linux, Windows and macOS (made from the 1024-pixel original) |
| `packaging/linux/MyMoney.desktop` | Our applications-menu entry for Linux (with `StartupWMClass`) |
| `src/main/resources/com/martinsmayhem/mymoney/icon-*.png`, `splash.png` | The window icons and splash screen, inside the app itself |
| `.gitignore` | Keeps build output (`target/`, `dist/`) out of git |

> **Housekeeping:** `target/` was committed to git before the `.gitignore` was
> added, so git still tracks those old files. To stop tracking them (the files
> stay on disk), run once and commit:
> ```bash
> git rm -r --cached target
> git commit -m "Stop tracking build output"
> ```
