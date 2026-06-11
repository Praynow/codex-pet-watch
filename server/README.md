# Codex Watch Server

Windows 端用量桥接服务，提供给 Wear OS 手表应用读取。

服务内置 Codex CLI 用量读取器，默认读取：

```text
~\.codex\config.toml
~\.codex\sessions\**\*.jsonl
```

它不会读取浏览器数据、cookies、OpenAI auth token 或 Claude 文件，也不会启动 Codex 进程。

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
```

如果仍想使用外部 CodexBar Safe 项目的读取器，可设置：

```powershell
$env:CODEXBAR_SAFE_PATH = "C:\path\to\codexbar-win"
```

token 留空时，启动脚本会自动生成根目录的 `codex-watch-token.txt`。这个文件不会进入 Git。

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
