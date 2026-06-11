$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$localAdb = Join-Path $projectDir "tools\android-sdk\platform-tools\adb.exe"
$systemAdb = Get-Command adb -ErrorAction SilentlyContinue
if (Test-Path $localAdb) {
    $adb = $localAdb
} elseif ($systemAdb) {
    $adb = $systemAdb.Source
} else {
    throw "ADB not found. Install Android platform-tools or place them under tools\android-sdk."
}

& $adb kill-server
& $adb start-server
& $adb devices -l
