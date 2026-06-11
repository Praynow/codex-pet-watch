# Codex Pet Wear App

面向 Wear OS 的原生 Android 应用。默认只配置 USB 回退地址，不包含私人域名、局域网 IP 或 token。

## 功能

- 圆形手表界面，展示 Codex 5 小时额度、周额度和 token 用量。
- 宠物精灵图动画。
- 用量、互动、设置三个页面。
- 依次尝试多个服务地址。
- 服务不可达时保留最后一次有效数据并显示离线状态。
- 数据超过 5 分钟未刷新时显示 `STALE`。

## 配置服务地址

当前默认资源：

```xml
<string name="codex_usage_urls">http://127.0.0.1:8765/usage</string>
<string name="codex_watch_token"></string>
```

推荐用脚本写入地址和 token：

```powershell
.\set-watch-server-url.ps1 -Urls https://YOUR_PUBLIC_DOMAIN/usage,http://YOUR_PC_IP:8765/usage,http://127.0.0.1:8765/usage -Token YOUR_TOKEN
```

如果根目录 `config.local.ps1` 已经填写 `CODEX_WATCH_PUBLIC_URL` 或 `CODEX_WATCH_LAN_URL`，可以直接运行：

```powershell
.\set-watch-server-url.ps1
```

地址会从左到右依次尝试。推荐顺序是公网隧道、局域网地址、USB 回退地址。

## 构建

从项目根目录运行：

```powershell
.\build-apk.ps1
```

脚本会优先使用根目录 `tools/` 中的本地工具；如果没有，会使用 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 Gradle wrapper。

APK 输出：

```text
wear-app\app\build\outputs\apk\debug\app-debug.apk
```

## 安装到手表

从项目根目录运行：

```powershell
.\install-to-watch.ps1
```

脚本会检查 ADB 设备、安装 APK，并建立：

```text
watch 127.0.0.1:8765 -> PC 127.0.0.1:8765
```

OPPO Watch X2 测试时，USB 调试就够用，不需要蓝牙调试。
