$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
Set-Location $projectDir

$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    Write-Host "Python was not found. Install Python 3.10+ first." -ForegroundColor Red
    exit 1
}

$port = if ($env:CODEX_WATCH_PORT) { $env:CODEX_WATCH_PORT } else { "8765" }
$tokenPath = Join-Path $projectDir "codex-watch-token.txt"
$token = $env:CODEX_WATCH_TOKEN
if (-not $token -and (Test-Path $tokenPath)) {
    $token = (Get-Content $tokenPath -Raw).Trim()
}
if (-not $token) {
    $bytes = New-Object byte[] 24
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    $token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    Set-Content -Path $tokenPath -Value $token
}

Write-Host "Starting Codex Watch usage server..." -ForegroundColor Cyan
Write-Host "Local URL: http://127.0.0.1:$port/usage" -ForegroundColor Green
Write-Host "Token protection enabled." -ForegroundColor Green

$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.PrefixOrigin -ne "WellKnown" } |
    Select-Object -ExpandProperty IPAddress

foreach ($address in $addresses) {
    Write-Host "Watch URL candidate: http://$address`:$port/usage" -ForegroundColor Green
}

python .\server\codex_watch_server.py --host 0.0.0.0 --port $port --token $token
