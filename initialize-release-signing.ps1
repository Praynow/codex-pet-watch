param(
    [string]$Alias = "codex-pet-watch",
    [string]$DistinguishedName = "CN=Codex Pet Watch, O=Praynow, C=CN"
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$wearDir = Join-Path $projectDir "wear-app"
$privateDir = Join-Path $projectDir "private-signing"
$keystorePath = Join-Path $privateDir "codex-pet-watch-release.jks"
$propertiesPath = Join-Path $wearDir "keystore.properties"

if (Test-Path -LiteralPath $keystorePath) {
    throw "Release keystore already exists; refusing to overwrite it."
}
if (Test-Path -LiteralPath $propertiesPath) {
    throw "wear-app\keystore.properties already exists; refusing to overwrite it."
}

$localJdk = Get-ChildItem (Join-Path $projectDir "tools\jdk") -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1
$microsoftJdkRoot = Join-Path $env:ProgramFiles "Microsoft"
$installedJdk = Get-ChildItem $microsoftJdkRoot -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($localJdk) {
    $jdkHome = $localJdk.FullName
} elseif ($env:JAVA_HOME) {
    $jdkHome = $env:JAVA_HOME
} elseif ($installedJdk) {
    $jdkHome = $installedJdk.FullName
} else {
    throw "JDK 17+ not found."
}
$keytool = Join-Path $jdkHome "bin\keytool.exe"
if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool.exe not found in the selected JDK."
}

$passwordBytes = New-Object byte[] 36
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($passwordBytes)
} finally {
    $random.Dispose()
}
$password = [Convert]::ToBase64String($passwordBytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
$env:CODEX_WATCH_RELEASE_PASSWORD = $password

try {
    New-Item -ItemType Directory -Path $privateDir -ErrorAction Stop | Out-Null
    & $keytool `
        -genkeypair `
        -alias $Alias `
        -keyalg RSA `
        -keysize 3072 `
        -validity 10000 `
        -dname $DistinguishedName `
        -keystore $keystorePath `
        -storetype PKCS12 `
        -storepass:env CODEX_WATCH_RELEASE_PASSWORD `
        -keypass:env CODEX_WATCH_RELEASE_PASSWORD `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }

    $properties = @(
        "storeFile=../private-signing/codex-pet-watch-release.jks",
        "storePassword=$password",
        "keyAlias=$Alias",
        "keyPassword=$password"
    )
    [System.IO.File]::WriteAllLines(
        $propertiesPath,
        $properties,
        [System.Text.UTF8Encoding]::new($false)
    )

    & $keytool `
        -list `
        -keystore $keystorePath `
        -storepass:env CODEX_WATCH_RELEASE_PASSWORD `
        -alias $Alias *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "The generated release keystore could not be verified."
    }
} finally {
    $env:CODEX_WATCH_RELEASE_PASSWORD = $null
    Remove-Variable password,passwordBytes -ErrorAction SilentlyContinue
}

Write-Host "Release signing initialized without printing passwords." -ForegroundColor Green
Write-Host "Back up both private-signing\codex-pet-watch-release.jks and wear-app\keystore.properties." -ForegroundColor Yellow
