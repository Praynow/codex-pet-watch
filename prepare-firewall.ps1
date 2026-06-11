$ErrorActionPreference = "Stop"

$ruleName = "Codex Watch Server 8765"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Firewall rule already exists: $ruleName" -ForegroundColor Green
    exit 0
}

New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 8765 `
    -Profile Private,Domain | Out-Null

Write-Host "Firewall rule added: $ruleName" -ForegroundColor Green
