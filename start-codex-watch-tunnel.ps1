param(
    [string]$CloudflareTunnelToken
)

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$cloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
$cloudflaredExe = $null
if ($cloudflared) {
    $cloudflaredExe = $cloudflared.Source
}
if (-not $cloudflared) {
    $cloudflaredPath = Join-Path $projectDir "tools\cloudflared\cloudflared.exe"
    if (Test-Path $cloudflaredPath) {
        $cloudflaredExe = $cloudflaredPath
    }
}
if (-not $cloudflaredExe) {
    throw "cloudflared was not found. Install it or place cloudflared.exe under tools\cloudflared."
}

$token = if ($CloudflareTunnelToken) { $CloudflareTunnelToken } else { $env:CLOUDFLARED_TUNNEL_TOKEN }
if ($token) {
    $hadTunnelToken = Test-Path Env:TUNNEL_TOKEN
    $previousTunnelToken = $env:TUNNEL_TOKEN
    $env:TUNNEL_TOKEN = $token
    try {
        & $cloudflaredExe tunnel --no-autoupdate run
    } finally {
        if ($hadTunnelToken) {
            $env:TUNNEL_TOKEN = $previousTunnelToken
        } else {
            Remove-Item Env:TUNNEL_TOKEN -ErrorAction SilentlyContinue
        }
    }
} else {
    Write-Host "Starting a temporary Quick Tunnel. Its URL changes whenever the tunnel restarts." -ForegroundColor Yellow
    & $cloudflaredExe tunnel --url http://127.0.0.1:8765
}
