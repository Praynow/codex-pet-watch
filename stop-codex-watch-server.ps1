$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidPath = Join-Path $projectDir "codex-watch-server.pid"

function Get-CodexWatchServerProcess {
    param(
        [int]$ProcessId
    )

    if ($ProcessId -le 0) {
        return $null
    }

    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $processInfo) {
        return $null
    }

    $processName = [string]$processInfo.Name
    $commandLine = [string]$processInfo.CommandLine
    if ($processName -notin @("python.exe", "python3.exe", "py.exe")) {
        return $null
    }
    if ($commandLine -notmatch "codex_watch_server\.py") {
        return $null
    }

    return $processInfo
}

if (-not (Test-Path $pidPath)) {
    Write-Host "No server PID file found." -ForegroundColor Yellow
    exit 0
}

$pidValue = (Get-Content $pidPath -Raw).Trim()
if ($pidValue) {
    $serverPid = 0
    if ([int]::TryParse($pidValue, [ref]$serverPid)) {
        $process = Get-CodexWatchServerProcess -ProcessId $serverPid
        if ($process) {
            Stop-Process -Id $serverPid -Force
            Write-Host "Stopped Codex Watch server. PID: $pidValue" -ForegroundColor Green
        } else {
            Write-Host "Server PID file is stale or belongs to another process. Leaving that process alone." -ForegroundColor Yellow
        }
    } else {
        Write-Host "Server PID file is invalid." -ForegroundColor Yellow
    }
}

Remove-Item -LiteralPath $pidPath -ErrorAction SilentlyContinue
