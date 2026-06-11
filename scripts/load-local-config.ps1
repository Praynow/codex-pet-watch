$projectDir = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectDir "config.local.ps1"

if (Test-Path $configPath) {
    . $configPath
}
