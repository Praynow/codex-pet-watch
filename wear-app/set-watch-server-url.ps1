param(
    [string]$Url,
    [string[]]$Urls,
    [string]$Token,
    [switch]$PublicDefaults
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$configLoader = Join-Path $projectDir "scripts\load-local-config.ps1"
if (Test-Path $configLoader) {
    . $configLoader
}

$mainStringsPath = Join-Path $scriptDir "app\src\main\res\values\strings.xml"
$localStringsPath = Join-Path $scriptDir "app\src\debug\res\values\codex_watch_local.xml"
$stringsPath = if ($PublicDefaults) { $mainStringsPath } else { $localStringsPath }
$tokenPath = Join-Path $projectDir "codex-watch-token.txt"
$port = if ($env:CODEX_WATCH_PORT) { $env:CODEX_WATCH_PORT } else { "8765" }

function Normalize-UsageUrl {
    param([string]$Value)

    if (-not $Value) {
        return $null
    }

    $normalized = $Value.Trim()
    if (-not $normalized) {
        return $null
    }
    if ($normalized -notmatch "^https?://") {
        $normalized = "http://$normalized"
    }
    if ($normalized -notmatch "/usage(?:\?|$)") {
        $normalized = $normalized.TrimEnd("/") + "/usage"
    }
    return $normalized
}

function Ensure-StringResourceFile {
    param([string]$Path)

    if (Test-Path $Path) {
        return
    }

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null

    $doc = New-Object System.Xml.XmlDocument
    $resources = $doc.CreateElement("resources")
    $doc.AppendChild($resources) | Out-Null

    foreach ($name in @("codex_usage_urls", "codex_watch_token")) {
        $node = $doc.CreateElement("string")
        $attr = $doc.CreateAttribute("name")
        $attr.Value = $name
        $node.Attributes.Append($attr) | Out-Null
        if ($name -eq "codex_usage_urls") {
            $node.InnerText = "http://127.0.0.1:$port/usage"
        }
        $resources.AppendChild($node) | Out-Null
    }

    $doc.Save($Path)
}

function Get-OrAddStringNode {
    param(
        [xml]$Xml,
        [string]$Name
    )

    $node = $Xml.resources.string | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if ($node) {
        return $node
    }

    $node = $Xml.CreateElement("string")
    $attr = $Xml.CreateAttribute("name")
    $attr.Value = $Name
    $node.Attributes.Append($attr) | Out-Null
    $Xml.resources.AppendChild($node) | Out-Null
    return $node
}

if (-not $Url -and -not $Urls -and -not $Token) {
    $configuredUrls = @()
    $publicUrl = Normalize-UsageUrl $env:CODEX_WATCH_PUBLIC_URL
    $lanUrl = Normalize-UsageUrl $env:CODEX_WATCH_LAN_URL
    if ($publicUrl) {
        $configuredUrls += $publicUrl
    }
    if ($lanUrl) {
        $configuredUrls += $lanUrl
    }

    if ($configuredUrls.Count -gt 0) {
        $configuredUrls += "http://127.0.0.1:$port/usage"
        $Urls = $configuredUrls | Select-Object -Unique
        if ($env:CODEX_WATCH_TOKEN) {
            $Token = $env:CODEX_WATCH_TOKEN
        } elseif (Test-Path $tokenPath) {
            $Token = (Get-Content $tokenPath -Raw).Trim()
        }
    } else {
        $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.PrefixOrigin -ne "WellKnown" } |
            Select-Object -ExpandProperty IPAddress

        Write-Host "Pass the URL list to write into the Wear app config:" -ForegroundColor Cyan
        foreach ($address in $addresses) {
            Write-Host ".\set-watch-server-url.ps1 -Urls http://$address`:$port/usage,http://127.0.0.1:$port/usage" -ForegroundColor Green
        }
        Write-Host ""
        Write-Host "Or copy config.example.ps1 to config.local.ps1 and fill CODEX_WATCH_PUBLIC_URL or CODEX_WATCH_LAN_URL." -ForegroundColor Cyan
        exit 0
    }
}

Ensure-StringResourceFile $stringsPath
[xml]$xml = Get-Content $stringsPath
if ($Url -and -not $Urls) {
    $Urls = @(Normalize-UsageUrl $Url)
}

if ($Urls) {
    $Urls = $Urls | ForEach-Object { Normalize-UsageUrl $_ } | Where-Object { $_ } | Select-Object -Unique
    $urlsNode = Get-OrAddStringNode -Xml $xml -Name "codex_usage_urls"
    $urlsNode.InnerText = ($Urls -join ",")
    Write-Host "Updated codex_usage_urls to $($Urls -join ',')" -ForegroundColor Green
}

if ($Token) {
    $tokenNode = Get-OrAddStringNode -Xml $xml -Name "codex_watch_token"
    $tokenNode.InnerText = $Token
    Write-Host "Updated codex_watch_token." -ForegroundColor Green
    if (-not $PublicDefaults) {
        Set-Content -Path $tokenPath -Value $Token
        Write-Host "Updated local codex-watch-token.txt." -ForegroundColor Green
    }
}

$xml.Save($stringsPath)
if ($PublicDefaults) {
    Write-Host "Wrote public default resources: $stringsPath" -ForegroundColor Cyan
} else {
    Write-Host "Wrote local debug resources: $stringsPath" -ForegroundColor Cyan
}
