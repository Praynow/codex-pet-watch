$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$wearDir = Join-Path $projectDir "wear-app"
$signingFile = Join-Path $wearDir "keystore.properties"
if (-not (Test-Path -LiteralPath $signingFile)) {
    throw "Private wear-app\keystore.properties is required. Copy the example and fill it securely."
}

$signingValues = @{}
foreach ($line in Get-Content -LiteralPath $signingFile -Encoding utf8) {
    if ($line -match '^\s*([^#!][^=]*)=(.*)$') {
        $signingValues[$matches[1].Trim()] = $matches[2].Trim()
    }
}
foreach ($requiredKey in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
    if ([string]::IsNullOrWhiteSpace($signingValues[$requiredKey]) -or $signingValues[$requiredKey] -eq "CHANGE_ME") {
        throw "Signing property '$requiredKey' is missing or still uses the template value."
    }
}

$keystorePath = $signingValues["storeFile"]
if (-not [System.IO.Path]::IsPathRooted($keystorePath)) {
    $keystorePath = Join-Path $wearDir $keystorePath
}
if (-not (Test-Path -LiteralPath $keystorePath)) {
    throw "The configured release keystore does not exist."
}

$localJdk = Get-ChildItem (Join-Path $projectDir "tools\jdk") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
$microsoftJdkRoot = Join-Path $env:ProgramFiles "Microsoft"
$installedJdk = Get-ChildItem $microsoftJdkRoot -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $env:JAVA_HOME -and $localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
} elseif (-not $env:JAVA_HOME -and $installedJdk) {
    $env:JAVA_HOME = $installedJdk.FullName
}
if (-not $env:JAVA_HOME) {
    throw "JDK 17+ not found."
}

$localSdk = Join-Path $projectDir "tools\android-sdk"
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if (-not $env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $localSdk)) {
    $env:ANDROID_SDK_ROOT = $localSdk
}
if (-not $env:ANDROID_SDK_ROOT) {
    throw "Android SDK not found."
}

$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"
$gradle = Join-Path $wearDir "gradlew.bat"
$gradleExitCode = 0
Push-Location $wearDir
try {
    & $gradle :app:assembleRelease --no-daemon --no-watch-fs
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExitCode -ne 0) {
    throw "Gradle release build failed with exit code $gradleExitCode."
}

$apk = Join-Path $wearDir "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Gradle completed without producing the expected signed release APK."
}
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_SDK_ROOT "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw "Android build-tools not found for signature verification."
}
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
& $apksigner verify --verbose $apk
if ($LASTEXITCODE -ne 0) {
    throw "Release APK signature verification failed."
}
Write-Host "Signed release APK ready: $apk" -ForegroundColor Green
