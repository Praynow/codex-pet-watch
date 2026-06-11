$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $root "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$localAdb = Join-Path $root "tools\android-sdk\platform-tools\adb.exe"
$systemAdb = Get-Command adb -ErrorAction SilentlyContinue
$androidHome = Join-Path $env:USERPROFILE ".android"

if (Test-Path $localAdb) {
    $adb = $localAdb
} elseif ($systemAdb) {
    $adb = $systemAdb.Source
} else {
    throw "ADB not found. Install Android platform-tools or place them under tools\android-sdk."
}

Write-Host "Restarting ADB and refreshing this computer's USB debugging authorization key..." -ForegroundColor Cyan
& $adb kill-server | Out-Null

Remove-Item (Join-Path $androidHome "adbkey") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $androidHome "adbkey.pub") -Force -ErrorAction SilentlyContinue

& $adb start-server | Out-Null
Start-Sleep -Seconds 2
& $adb devices -l

Write-Host ""
Write-Host "If the watch shows 'Allow USB debugging?', tap Allow." -ForegroundColor Green
Write-Host "If no prompt appears, unlock the watch, toggle USB debugging off/on, then unplug and replug the cable." -ForegroundColor Yellow
