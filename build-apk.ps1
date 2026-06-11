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

Set-Location (Join-Path $projectDir "wear-app")
& $gradle :app:assembleDebug --no-daemon --no-watch-fs

$apk = Join-Path $projectDir "wear-app\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "APK ready: $apk" -ForegroundColor Green
