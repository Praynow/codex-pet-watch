# Codex Pet Watch

把 Codex 用量同步到 Wear OS 手表上的小工具。它包含一个 Windows 本地用量服务和一个原生 Wear OS 应用，可以在手表上查看 5 小时额度、周额度、今日 token、近 7 天 token，并通过小宠物状态显示当前使用情况。

## 功能概览

- Wear OS 圆形表盘界面，适配 OPPO Watch X2 等 Wear OS 手表。
- 默认使用纯代码绘制的占位宠物，不包含第三方宠物图片素材。
- 支持用户自行添加有授权的宠物 spritesheet。
- Windows 本地服务读取 Codex CLI 用量日志。
- 支持 USB 调试、局域网地址和公网隧道三种同步方式。
- 手表每 5 分钟自动刷新，也可以点击表盘立即刷新。
- 用量样本超过 5 分钟未更新时显示 `STALE`，避免误看旧数据。

## 项目结构

```text
.
├─ server/                         Windows 本地用量服务
│  ├─ codex_watch_server.py         HTTP API 服务
│  └─ codex_usage_reader.py         内置 Codex 用量读取器
├─ wear-app/                        Wear OS Android 应用
│  └─ app/src/main/assets/pets/      可选自定义宠物素材目录
├─ scripts/                         打包和辅助脚本
├─ config.example.ps1               本地配置模板
├─ build-apk.ps1                    构建调试 APK
└─ install-to-watch.ps1             安装到手表并建立 USB 反向隧道
```

## 工作原理

```text
Codex CLI logs
      ↓
Windows local server
      ↓
USB / LAN / Cloudflare tunnel
      ↓
Wear OS watch app
```

服务默认读取当前用户的 Codex CLI 本地数据：

```text
~\.codex\config.toml
~\.codex\sessions\**\*.jsonl
```

读取器只访问 Codex CLI 本地日志，不读取浏览器数据、cookies、OpenAI auth token 或 Claude 文件，也不会启动 Codex 进程。

## 准备环境

需要：

- Windows + PowerShell
- Python 3.10+
- JDK 17+
- Android SDK / platform-tools
- Wear OS 手表，并开启 USB 调试

Android SDK 和 platform-tools 可以通过 Android Studio 安装。项目脚本会优先使用本地 `tools/` 目录中的工具；如果没有，则使用系统环境中的 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 Gradle wrapper。

## 快速开始

复制配置模板：

```powershell
Copy-Item .\config.example.ps1 .\config.local.ps1
notepad .\config.local.ps1
```

通常不需要改 Codex 数据目录。服务默认读取：

```text
~\.codex
```

如果你的 Codex 数据目录不在默认位置，可在 `config.local.ps1` 中填写：

```powershell
$env:CODEX_WATCH_CODEX_DIR = "C:\path\to\.codex"
```

启动 Windows 用量服务：

```powershell
.\server\run-codex-watch-server.ps1
```

检查服务输出：

```powershell
python .\server\codex_watch_server.py --once
```

服务接口：

```text
http://127.0.0.1:8765/health
http://127.0.0.1:8765/usage
```

## 配置手表同步地址

进入手表应用目录：

```powershell
cd .\wear-app
```

如果只通过 USB 调试使用，默认地址已经够用：

```text
http://127.0.0.1:8765/usage
```

如果要通过局域网或公网隧道访问，在 `config.local.ps1` 中填写：

```powershell
$env:CODEX_WATCH_PUBLIC_URL = "https://YOUR_PUBLIC_DOMAIN/usage"
$env:CODEX_WATCH_LAN_URL = "http://YOUR_PC_IP:8765/usage"
$env:CODEX_WATCH_TOKEN = ""
```

然后写入手表应用配置：

```powershell
.\set-watch-server-url.ps1
```

也可以手动传入：

```powershell
.\set-watch-server-url.ps1 -Urls https://YOUR_PUBLIC_DOMAIN/usage,http://YOUR_PC_IP:8765/usage,http://127.0.0.1:8765/usage -Token YOUR_TOKEN
```

地址会从左到右依次尝试。推荐顺序是公网隧道、局域网地址、USB 回退地址。

默认情况下，脚本会写入本地 debug 覆盖文件：

```text
wear-app\app\src\debug\res\values\codex_watch_local.xml
```

这个文件被 Git 忽略，不会上传到 GitHub；但构建 debug APK 时会自动覆盖公开默认配置。脚本也会把 token 同步写入本地的 `codex-watch-token.txt`，让 Windows 服务和手表应用使用同一个 token。

如果你真的想修改公开默认资源，可以额外传入 `-PublicDefaults`。一般不建议这样做。

## 构建 APK

回到项目根目录：

```powershell
cd ..
```

构建调试 APK：

```powershell
.\build-apk.ps1
```

输出位置：

```text
wear-app\app\build\outputs\apk\debug\app-debug.apk
```

## 安装到手表

连接手表，开启 USB 调试并确认授权后运行：

```powershell
.\install-to-watch.ps1
```

安装脚本会：

- 检查 ADB 设备连接。
- 安装调试 APK。
- 建立 ADB 反向隧道，让手表访问电脑上的本地服务。
- 启动手表应用。

USB 调试时，手表会通过这个地址读取用量：

```text
http://127.0.0.1:8765/usage
```

## Cloudflare 隧道

如果想让手表离开局域网后继续同步，可以使用 Cloudflare 隧道。

设置命名隧道 token：

```powershell
$env:CLOUDFLARED_TUNNEL_TOKEN = "YOUR_TUNNEL_TOKEN"
.\start-codex-watch-tunnel.ps1
```

也可以把 token 写入 `config.local.ps1`。没有 token 时，脚本会尝试启动临时 Quick Tunnel。

## 自定义宠物素材

仓库默认不包含任何宠物图片素材，以避免版权风险。应用会在没有素材时显示一个纯代码绘制的占位宠物。

如果要添加自己的宠物素材，请创建：

```text
wear-app\app\src\main\assets\pets\YOUR_PET_ID\
```

目录内放入：

```text
pet.json
spritesheet.webp
```

`pet.json` 示例：

```json
{
  "id": "my-pet",
  "displayName": "My Pet",
  "description": "A custom pet I own or have permission to distribute.",
  "spritesheetPath": "spritesheet.webp"
}
```

请只提交你拥有版权、已获得授权、或明确可公开分发的素材。

## 本地配置和安全

仓库不会提交这些本地文件：

- `config.local.ps1`
- `codex-watch-token.txt`
- `wear-app/app/src/debug/res/values/codex_watch_local.xml`
- `wear-app/app/src/debug/assets/pets/`
- `wear-app/local.properties`
- 日志、pid、APK、构建产物
- 本机 `tools/` 工具链缓存
- 手动验证截图
- 未确认授权的宠物图片素材

公开前可以生成一个干净源码包：

```powershell
.\scripts\create-source-package.ps1
```

输出：

```text
dist\codex-watch-pet-source.zip
```

## 开源注意事项

核心用量读取器已经包含在 `server/codex_usage_reader.py`，不依赖外部 CodexBar 项目。

发布前请确认已添加你希望使用的开源许可证。

`server/codex_usage_reader.py` 的部分逻辑来自 CodexBar Safe，相关 MIT 许可证声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
