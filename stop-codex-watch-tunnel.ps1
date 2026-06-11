$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidPath = Join-Path $projectDir "codex-watch-tunnel.pid"

if (-not (Test-Path $pidPath)) {
    Write-Host "No tunnel PID file found." -ForegroundColor Yellow
    exit 0
}

$pidValue = (Get-Content $pidPath -Raw).Trim()
if ($pidValue) {
    $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $process.Id -Force
        Write-Host "Stopped Codex Watch tunnel. PID: $pidValue" -ForegroundColor Green
    }
}

Remove-Item $pidPath -ErrorAction SilentlyContinue
