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

function Get-CodexWatchServerProcess {
    param(
        [int]$ProcessId
    )

    if ($ProcessId -le 0) {
        return $null
    }

    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $processInfo) {
        return $null
    }

    $processName = [string]$processInfo.Name
    $commandLine = [string]$processInfo.CommandLine
    if ($processName -notin @("python.exe", "python3.exe", "py.exe")) {
        return $null
    }
    if ($commandLine -notmatch "codex_watch_server\.py") {
        return $null
    }

    return $processInfo
}

function Repair-DuplicateProcessEnvironmentNames {
    $variables = [System.Environment]::GetEnvironmentVariables()
    $groups = @($variables.Keys | Group-Object { ([string]$_).ToLowerInvariant() } | Where-Object Count -gt 1)
    foreach ($group in $groups) {
        $keys = @($group.Group | ForEach-Object { [string]$_ })
        $preferredKey = $keys | Where-Object { $_ -ceq "Path" } | Select-Object -First 1
        if (-not $preferredKey) {
            $preferredKey = $keys[0]
        }
        if ($group.Name -eq "path") {
            $segments = New-Object System.Collections.Generic.List[string]
            foreach ($key in $keys) {
                foreach ($segment in ([string]$variables[$key] -split ";")) {
                    $trimmed = $segment.Trim()
                    if ($trimmed -and -not $segments.Contains($trimmed)) {
                        $segments.Add($trimmed)
                    }
                }
            }
            $value = $segments -join ";"
        } else {
            $value = [string]$variables[$preferredKey]
        }
        foreach ($key in $keys) {
            [System.Environment]::SetEnvironmentVariable($key, $null, "Process")
        }
        [System.Environment]::SetEnvironmentVariable($preferredKey, $value, "Process")
    }
}

if (Test-Path $pidPath) {
    $existingPidText = (Get-Content $pidPath -Raw).Trim()
    $existingPid = 0
    if ($existingPidText -and [int]::TryParse($existingPidText, [ref]$existingPid)) {
        $existingProcess = Get-CodexWatchServerProcess -ProcessId $existingPid
        if ($existingProcess) {
            Write-Host "Codex Watch server is already running. PID: $existingPid" -ForegroundColor Green
            exit 0
        }
    }

    Write-Host "Removing stale server PID file." -ForegroundColor Yellow
    Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
}

$listener = Get-NetTCPConnection -LocalPort ([int]$port) -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $listenerProcess = Get-CodexWatchServerProcess -ProcessId ([int]$listener.OwningProcess)
    if ($listenerProcess) {
        Set-Content -Path $pidPath -Value $listener.OwningProcess
        Write-Host "Codex Watch server is already listening. PID: $($listener.OwningProcess)" -ForegroundColor Green
        exit 0
    }

    $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $ownerName = if ($owner) { $owner.ProcessName } else { "unknown" }
    throw "Port $port is already in use by PID $($listener.OwningProcess) ($ownerName)."
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

$arguments = @(".\server\codex_watch_server.py", "--host", "0.0.0.0", "--port", $port)
$hadTokenEnvironment = Test-Path Env:CODEX_WATCH_TOKEN
$previousTokenEnvironment = $env:CODEX_WATCH_TOKEN
$env:CODEX_WATCH_TOKEN = $token
try {
    Repair-DuplicateProcessEnvironmentNames
    $process = Start-Process -FilePath "python" -ArgumentList $arguments -WorkingDirectory $projectDir -RedirectStandardOutput $outPath -RedirectStandardError $errPath -WindowStyle Hidden -PassThru
} finally {
    if ($hadTokenEnvironment) {
        $env:CODEX_WATCH_TOKEN = $previousTokenEnvironment
    } else {
        Remove-Item Env:CODEX_WATCH_TOKEN -ErrorAction SilentlyContinue
    }
}
Set-Content -Path $pidPath -Value $process.Id

Start-Sleep -Seconds 2
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
    throw "Codex Watch server exited immediately. Check $errPath"
}

$deadline = (Get-Date).AddSeconds(8)
$serverListener = $null
do {
    $serverListener = Get-NetTCPConnection -LocalPort ([int]$port) -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $process.Id } |
        Select-Object -First 1
    if ($serverListener) {
        break
    }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

if (-not $serverListener) {
    throw "Codex Watch server started but did not listen on port $port. Check $errPath"
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
