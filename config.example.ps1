# Copy this file to config.local.ps1 and fill in values for your machine.
# config.local.ps1 is ignored by Git.

# Optional Codex CLI data directory. Leave empty to use ~/.codex.
# Example: C:\path\to\.codex
$env:CODEX_WATCH_CODEX_DIR = ""

# Optional external CodexBar Safe folder for compatibility.
# Leave empty to use the built-in server/codex_usage_reader.py.
$env:CODEXBAR_SAFE_PATH = ""

# Local server port.
$env:CODEX_WATCH_PORT = "8765"

# Optional fixed token. Leave empty to let the server generate codex-watch-token.txt.
$env:CODEX_WATCH_TOKEN = ""

# Optional public tunnel URL for the watch app.
# Example: https://your-domain.example.com/usage
$env:CODEX_WATCH_PUBLIC_URL = ""

# Optional LAN URL for the watch app.
# Example: http://YOUR_PC_IP:8765/usage
$env:CODEX_WATCH_LAN_URL = ""

# Optional banked usage-reset cards. Group cards with the same expiry using count.
# Use YYYY-MM-DD or ISO 8601 timestamps. Keep the JSON on one line in PowerShell.
$env:CODEX_WATCH_RESET_CARDS_JSON = '[]'

# Legacy single-card setting. Used only when CODEX_WATCH_RESET_CARDS_JSON is empty.
$env:CODEX_WATCH_RESET_CARD_EXPIRES_AT = ""

# Optional in-app update deployment. The APK path is private machine state.
# The endpoint stays unavailable until CODEX_WATCH_UPDATE_APK_PATH points to a valid APK.
$env:CODEX_WATCH_UPDATE_APK_PATH = ""
$env:CODEX_WATCH_UPDATE_APK_URL = "https://your-domain.example.com/downloads/codex-pet-watch.apk"
$env:CODEX_WATCH_UPDATE_VERSION_CODE = "2"
$env:CODEX_WATCH_UPDATE_VERSION_NAME = "0.2.0"
$env:CODEX_WATCH_UPDATE_REQUIRED = "false"
$env:CODEX_WATCH_UPDATE_NOTES = ""

# Optional Cloudflare named tunnel token.
$env:CLOUDFLARED_TUNNEL_TOKEN = ""
