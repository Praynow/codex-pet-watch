$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$pidPath = Join-Path $projectDir "codex-watch-server.pid"
$outPath = Join-Path $projectDir "codex-watch-server.out.log"
$errPath = Join-Path $projectDir "codex-watch-server.err.log"
$tokenPath = Join-Path $projectDir "codex-watch-token.txt"
$port = if ($env:CODEX_WATCH_PORT) { $env:CODEX_WATCH_PORT } else { "8765" }

if (Test-Path $pidPath) {
    $existingPid = (Get-Content $pidPath -Raw).Trim()
    if ($existingPid) {
        $existingProcess = Get-Process -Id ([int]$existingPid) -ErrorAction SilentlyContinue
        if ($existingProcess) {
            Write-Host "Codex Watch server is already running. PID: $existingPid" -ForegroundColor Green
            exit 0
        }
    }
}

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

$arguments = @(".\server\codex_watch_server.py", "--host", "0.0.0.0", "--port", $port, "--token", $token)
$process = Start-Process -FilePath "python" -ArgumentList $arguments -WorkingDirectory $projectDir -RedirectStandardOutput $outPath -RedirectStandardError $errPath -WindowStyle Hidden -PassThru
Set-Content -Path $pidPath -Value $process.Id

Start-Sleep -Seconds 2
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
    throw "Codex Watch server exited immediately. Check $errPath"
}

Write-Host "Codex Watch server is running. PID: $($process.Id)" -ForegroundColor Green
Write-Host "Local URL: http://127.0.0.1:$port/usage" -ForegroundColor Green

$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.PrefixOrigin -ne "WellKnown" } |
    Select-Object -ExpandProperty IPAddress

foreach ($address in $addresses) {
    Write-Host "LAN URL candidate: http://$address`:$port/usage" -ForegroundColor Green
}

Write-Host "Token protection enabled." -ForegroundColor Green
