$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidPath = Join-Path $projectDir "codex-watch-server.pid"

if (-not (Test-Path $pidPath)) {
    Write-Host "No server PID file found." -ForegroundColor Yellow
    exit 0
}

$pidValue = Get-Content $pidPath -Raw
if ($pidValue) {
    $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped Codex Watch server. PID: $pidValue" -ForegroundColor Green
    }
}

Remove-Item $pidPath -ErrorAction SilentlyContinue
