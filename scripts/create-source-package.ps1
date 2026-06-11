param(
    [string]$PackageName = "codex-watch-pet-source"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$distDir = Join-Path $projectDir "dist"
$stageDir = Join-Path $distDir $PackageName
$zipPath = Join-Path $distDir "$PackageName.zip"
$rootPath = (Resolve-Path $projectDir).Path.TrimEnd("\")
$distPath = (Resolve-Path (New-Item -ItemType Directory -Path $distDir -Force)).Path

if ($stageDir -notlike "$distPath*") {
    throw "Refusing to clean a staging directory outside dist: $stageDir"
}

Remove-Item -LiteralPath $stageDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

$excludedDirPrefixes = @(
    ".git/",
    ".idea/",
    ".vscode/",
    "dist/",
    "tools/",
    "wear-app/.gradle/",
    "wear-app/app/src/debug/assets/pets/",
    "wear-app/app/build/"
)

$excludedExactFiles = @(
    ".env",
    "config.local.ps1",
    "codex-watch-token.txt",
    "wear-app/app/src/debug/res/values/codex_watch_local.xml",
    "wear-app/local.properties"
)

$excludedFilePatterns = @(
    "*.aab",
    "*.apk",
    "*.ap_",
    "*.jks",
    "*.keystore",
    "*.log",
    "*.p12",
    "*.pyc",
    "*.pyo",
    "*.pid",
    "watch-screen*.png",
    "watch-ui-preview*.png"
)

function Test-ExcludedPath {
    param([string]$RelativePath)

    $normalized = $RelativePath -replace "\\", "/"
    foreach ($prefix in $excludedDirPrefixes) {
        if ($normalized.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    if ($normalized.StartsWith("__pycache__/", [System.StringComparison]::OrdinalIgnoreCase) -or
        $normalized.IndexOf("/__pycache__/", [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        return $true
    }
    foreach ($exact in $excludedExactFiles) {
        if ($normalized.Equals($exact, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }

    $leaf = Split-Path $RelativePath -Leaf
    foreach ($pattern in $excludedFilePatterns) {
        if ($leaf -like $pattern) {
            return $true
        }
    }

    return $false
}

Get-ChildItem -LiteralPath $projectDir -Recurse -File -Force |
    ForEach-Object {
        $relative = $_.FullName.Substring($rootPath.Length + 1)
        if (Test-ExcludedPath $relative) {
            return
        }

        $target = Join-Path $stageDir $relative
        $targetDir = Split-Path -Parent $target
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }

Compress-Archive -Path (Join-Path $stageDir "*") -DestinationPath $zipPath -Force
Write-Host "Source package ready: $zipPath" -ForegroundColor Green
