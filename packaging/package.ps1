<#
  Builds the MyMoney installer for Windows, with its own trimmed-down Java
  runtime inside, so users don't need Java. (Linux and macOS: use
  packaging/package.sh.)  See docs/PACKAGING.md for the why.

    powershell -ExecutionPolicy Bypass -File packaging\package.ps1          an .msi installer
    powershell -ExecutionPolicy Bypass -File packaging\package.ps1 exe      an .exe installer
    powershell -ExecutionPolicy Bypass -File packaging\package.ps1 app-image  just the app folder (to test)

  Needs: a JDK 26 (JAVA_HOME set to it), Maven (mvn on the PATH, or $env:MVN),
  and for .msi/.exe the WiX Toolset (https://wixtoolset.org; jpackage finds it
  on the PATH).

  The installer lands in dist\.
#>
param([string]$Type = "msi")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")        # the project folder

# ----- Tools -----------------------------------------------------------------
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to a JDK 26." }
$bin = Join-Path $env:JAVA_HOME "bin"
$mvn = if ($env:MVN) { $env:MVN } else { "mvn" }

# ----- What we're building ---------------------------------------------------
# The project's version (the first <version> in pom.xml), as installers want it: numbers only.
$version = ([regex]::Match((Get-Content pom.xml -Raw), "<version>([^<]+)</version>")).Groups[1].Value
$appVersion = $version -replace "-.*$", ""         # 1.0.0-SNAPSHOT -> 1.0.0
Write-Host "== MyMoney $version`: building a $Type for Windows"

# ----- 1. Compile, test and build the jar (target\MyMoney.jar, target\lib\) ---
& $mvn -q -B clean package
if ($LASTEXITCODE -ne 0) { throw "The Maven build failed." }

# ----- 2. Gather what goes in the app (in a local temporary folder) -----------
$build = Join-Path ([IO.Path]::GetTempPath()) ("mymoney-build-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path "$build\input", "dist" | Out-Null
try {
    Copy-Item target\MyMoney.jar "$build\input\"
    Copy-Item target\lib "$build\input\lib" -Recurse

    # ----- 3. A Java runtime with just the modules MyMoney uses --------------
    $modules = & "$bin\jdeps" --multi-release 26 --ignore-missing-deps --print-module-deps `
                   --class-path "target\lib\*" target\MyMoney.jar
    Write-Host "== Java modules needed: $modules"
    & "$bin\jlink" --add-modules $modules --strip-debug --no-man-pages --no-header-files `
        --compress=zip-6 --output "$build\runtime"
    if ($LASTEXITCODE -ne 0) { throw "jlink failed." }

    # ----- 4. Package it -----------------------------------------------------
    $jpArgs = @(
        "--type", $Type,
        "--name", "MyMoney",
        "--app-version", $appVersion,
        "--vendor", "Martin's Mayhem",
        "--description", "MyMoney: personal finance",
        "--copyright", "Copyright $((Get-Date).Year) Mike Martin",
        "--input", "$build\input",
        "--main-jar", "MyMoney.jar",
        "--main-class", "com.martinsmayhem.mymoney.MyMoney",
        "--runtime-image", "$build\runtime",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--icon", "packaging\icons\MyMoney.ico",
        "--dest", "$build\out"
    )
    if ($Type -ne "app-image") {
        $jpArgs += @(
            "--win-menu",                          # a Start menu entry
            "--win-menu-group", "MyMoney",
            "--win-shortcut",                      # a desktop shortcut
            "--win-shortcut-prompt",               # (asking first)
            "--win-dir-chooser",                   # let the user pick where it's installed
            # Never change this: it's how a newer installer knows to replace the installed MyMoney.
            "--win-upgrade-uuid", "8c763944-fcbf-4ab3-abca-f6dc9a4c21dd"
        )
    }
    & "$bin\jpackage" @jpArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

    Copy-Item "$build\out\*" dist\ -Recurse -Force
    Write-Host "== Done:"
    Get-ChildItem dist
}
finally {
    Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
}
