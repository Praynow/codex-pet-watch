$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$localJdk = Get-ChildItem (Join-Path $projectDir "tools\jdk") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $env:JAVA_HOME -and $localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
}

$microsoftJdkRoot = Join-Path $env:ProgramFiles "Microsoft"
$installedJdk = Get-ChildItem $microsoftJdkRoot -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $env:JAVA_HOME -and $installedJdk) {
    $env:JAVA_HOME = $installedJdk.FullName
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $env:JAVA_HOME -and -not $java) {
    throw "JDK not found. Install JDK 17+ or place it under tools\jdk."
}

$localSdk = Join-Path $projectDir "tools\android-sdk"
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if (-not $env:ANDROID_SDK_ROOT -and (Test-Path $localSdk)) {
    $env:ANDROID_SDK_ROOT = $localSdk
}
if (-not $env:ANDROID_SDK_ROOT) {
    throw "Android SDK not found. Install it with Android Studio or set ANDROID_SDK_ROOT."
}

if ($env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
$platformTools = Join-Path $env:ANDROID_SDK_ROOT "platform-tools"
if (Test-Path $platformTools) {
    $env:Path = "$platformTools;$env:Path"
}

$localGradle = Join-Path $projectDir "tools\gradle\gradle-8.10.2\bin\gradle.bat"
$wrapperGradle = Join-Path $projectDir "wear-app\gradlew.bat"
$systemGradle = Get-Command gradle -ErrorAction SilentlyContinue
if (Test-Path $localGradle) {
    $gradle = $localGradle
} elseif (Test-Path $wrapperGradle) {
    $gradle = $wrapperGradle
} elseif ($systemGradle) {
    $gradle = $systemGradle.Source
} else {
    throw "Gradle not found. Keep wear-app\gradlew.bat in the repository or install Gradle."
}

$gradleExitCode = 0
Push-Location (Join-Path $projectDir "wear-app")
try {
    & $gradle :app:assembleDebug --no-daemon --no-watch-fs
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExitCode -ne 0) {
    throw "Gradle debug build failed with exit code $gradleExitCode."
}

$apk = Join-Path $projectDir "wear-app\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Gradle completed without producing the expected debug APK."
}
Write-Host "APK ready: $apk" -ForegroundColor Green
