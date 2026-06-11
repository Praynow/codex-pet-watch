$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$localAdb = Join-Path $projectDir "tools\android-sdk\platform-tools\adb.exe"
$systemAdb = Get-Command adb -ErrorAction SilentlyContinue
$apk = Join-Path $projectDir "wear-app\app\build\outputs\apk\debug\app-debug.apk"

if (Test-Path $localAdb) {
    $adb = $localAdb
} elseif ($systemAdb) {
    $adb = $systemAdb.Source
} else {
    throw "ADB not found. Install Android platform-tools or place them under tools\android-sdk."
}
if (-not (Test-Path $apk)) {
    throw "APK not found. Run .\build-apk.ps1 first."
}

Write-Host "Checking connected devices..." -ForegroundColor Cyan
$devicesOutput = & $adb devices
$devicesOutput

$deviceLines = $devicesOutput | Where-Object { $_ -match "`t(device|unauthorized|offline)$" }
if (-not $deviceLines) {
    Write-Host ""
    Write-Host "No watch is visible to ADB yet." -ForegroundColor Yellow
    Write-Host "Connect the watch by USB, enable USB debugging, and accept the authorization prompt on the watch." -ForegroundColor Yellow
    exit 1
}

if ($deviceLines -match "unauthorized") {
    Write-Host ""
    Write-Host "The watch is connected but not authorized. Accept the USB debugging prompt on the watch, then run this again." -ForegroundColor Yellow
    exit 1
}

if ($deviceLines -match "offline") {
    Write-Host ""
    Write-Host "The watch is offline. Unplug/replug the cable and try again." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "Preparing USB data tunnel for the local usage server..." -ForegroundColor Cyan
try {
    & $adb reverse tcp:8765 tcp:8765 | Out-Null
    Write-Host "USB tunnel ready: watch 127.0.0.1:8765 -> PC 127.0.0.1:8765"
} catch {
    Write-Host "USB tunnel could not be prepared. LAN URL fallback may still work." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Installing Codex Pet to the connected watch..." -ForegroundColor Cyan
& $adb install -r $apk

Write-Host ""
Write-Host "Launching Codex Pet..." -ForegroundColor Cyan
& $adb shell monkey -p com.codexwatch.pet 1
