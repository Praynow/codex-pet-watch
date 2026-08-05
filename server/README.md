# Codex Watch Server

Windows 端用量桥接服务，提供给 Wear OS 手表应用读取。

服务内置 Codex CLI 用量读取器，默认读取：

```text
~\.codex\config.toml
~\.codex\sessions\**\*.jsonl
```

它不会读取浏览器数据、cookies、OpenAI auth token 或 Claude 文件，也不会启动 Codex 进程。

服务会从最新的 Codex 会话事件中读取实际使用的模型和 effort。手表只显示实际模型，不提供需要随模型发布手动维护的选择列表；`config.toml` 中的模型只在会话尚无模型信息时作为回退。

## 配置

复制根目录配置模板：

```powershell
Copy-Item ..\config.example.ps1 ..\config.local.ps1
notepad ..\config.local.ps1
```

通常无需填写读取器路径。如果 Codex 数据目录不在默认位置，填写：

```powershell
$env:CODEX_WATCH_CODEX_DIR = "C:\path\to\.codex"
```

可选配置：

```powershell
$env:CODEX_WATCH_PORT = "8765"
$env:CODEX_WATCH_TOKEN = ""
$env:CODEX_WATCH_RESET_CARDS_JSON = '[]'
$env:CODEX_WATCH_RESET_CARD_EXPIRES_AT = ""
```

`CODEX_WATCH_RESET_CARDS_JSON` 用于记录多张卡，可按到期时间分组，例如：

```powershell
$env:CODEX_WATCH_RESET_CARDS_JSON = '[{"expires_at":"2030-01-15T09:00:00+08:00","count":2},{"expires_at":"2030-02-01","count":1}]'
```

API 的 `reset_cards` 字段会返回 `total_count`、`usable_count`、`urgent_count`、`expired_count`、最近到期批次和完整批次数组。`reset_card` 保留为最近到期批次，兼容旧手表版本。`CODEX_WATCH_RESET_CARD_EXPIRES_AT` 仅在多卡列表为空时作为单卡回退。

本地日志目前不包含可靠的重置卡授予/到期字段，因此服务不会猜测，也不会访问浏览器或账号页面；未配置时 API 返回 `reset_cards.available=false`。

如果仍想使用外部 CodexBar Safe 项目的读取器，可设置：

```powershell
$env:CODEXBAR_SAFE_PATH = "C:\path\to\codexbar-win"
```

token 留空时，启动脚本会自动生成根目录的 `codex-watch-token.txt`。这个文件不会进入 Git。认证只接受 `X-Codex-Watch-Token` 请求头，避免 token 出现在 URL、代理日志或浏览器历史中；启动脚本通过环境变量传递 token，不把它写进进程命令行。

## 运行

从项目根目录运行：

```powershell
.\server\run-codex-watch-server.ps1
```

接口：

- `http://127.0.0.1:8765/health`
- `http://127.0.0.1:8765/usage`

检查一次 JSON 输出：

```powershell
python .\server\codex_watch_server.py --once
```

手表真机走 Wi-Fi 时，请使用启动脚本打印出的局域网地址。USB 调试时，安装脚本会准备 ADB 反向隧道。
