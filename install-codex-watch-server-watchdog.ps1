$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverScript = Join-Path $projectDir "start-codex-watch-server.ps1"
$hiddenLauncher = Join-Path $projectDir "start-codex-watch-server-hidden.vbs"
$taskName = "Codex Pet Watch Server"

if (-not (Test-Path $serverScript)) {
    throw "Missing server start script: $serverScript"
}
if (-not (Test-Path $hiddenLauncher)) {
    throw "Missing hidden launcher: $hiddenLauncher"
}

$quotedLauncher = '"' + $hiddenLauncher + '"'
$wscriptPath = Join-Path $env:SystemRoot "System32\wscript.exe"
$action = New-ScheduledTaskAction `
    -Execute $wscriptPath `
    -Argument $quotedLauncher

$startupTrigger = New-ScheduledTaskTrigger -AtStartup
$logonTrigger = New-ScheduledTaskTrigger -AtLogOn
$repeatTrigger = New-ScheduledTaskTrigger `
    -Once `
    -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 5) `
    -RepetitionDuration (New-TimeSpan -Days 3650)

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew

$userId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal `
    -UserId $userId `
    -LogonType S4U `
    -RunLevel Limited

Register-ScheduledTask `
    -TaskName $taskName `
    -Action $action `
    -Trigger @($startupTrigger, $logonTrigger, $repeatTrigger) `
    -Settings $settings `
    -Principal $principal `
    -Description "Keeps the Codex Pet Watch local usage server running on port 8765." `
    -Force | Out-Null

$task = Get-ScheduledTask -TaskName $taskName
$task.Settings.Hidden = $true
Set-ScheduledTask -InputObject $task | Out-Null

Start-ScheduledTask -TaskName $taskName

Write-Host "Installed watchdog task: $taskName" -ForegroundColor Green
