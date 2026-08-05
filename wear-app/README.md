# Codex Pet Wear App

面向 Wear OS 的原生 Android 应用。默认只配置 USB 回退地址，不包含私人域名、局域网 IP 或 token。

## 功能

- 圆形手表界面，展示 Codex 5 小时额度、周额度、token 用量和多张重置卡到期提醒。
- 模型名称由服务从最新 Codex 会话自动识别；应用不再维护模型和 effort 选择列表。
- 内置代码占位宠物，并自动加载项目中已确认用于手表的宠物 spritesheet。
- 可继续添加有授权的宠物 spritesheet；互动页覆盖 9 个标准动作状态。
- 用量、互动、设置三个页面。
- 设置页支持通过受控 HTTPS 元数据检查更新、校验 APK SHA-256，并交给系统安装器请求用户确认。
- 依次尝试多个服务地址。
- 服务不可达时保留最后一次有效数据并显示离线状态。
- 数据超过 5 分钟未刷新时显示 `STALE`。
- 首页显示可用重置卡总数；设置页点击 `RESET CARDS` 可轮换查看各批次的数量、日期和时间。

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

脚本默认写入本地 debug 覆盖文件：

```text
app\src\debug\res\values\codex_watch_local.xml
```

这个文件被 Git 忽略，不会上传。debug APK 构建时会自动使用它覆盖公开默认地址和 token。脚本也会同步更新根目录的 `codex-watch-token.txt`。

如果要修改公开默认资源，可以传入 `-PublicDefaults`，但开源发布前通常不需要这样做。

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

源码版本为 `versionCode 2`、`versionName 0.2.0`。debug APK 使用 Android debug 证书，仅用于覆盖已有 debug 安装。

## 固定 release 签名

复制不含秘密的模板，并把 keystore 放在私密、已备份的位置：

```powershell
Copy-Item .\keystore.properties.example .\keystore.properties
notepad .\keystore.properties
..\build-release-apk.ps1
```

`keystore.properties`、`*.jks` 与 `*.keystore` 都被 Git 忽略。发布后必须始终使用同一份 keystore；丢失它将无法继续覆盖升级已安装应用。构建脚本会验证 release APK 确实带有签名。

更新元数据固定从 `https://watch.sadjuly.xyz/update` 读取；APK URL 也必须是无用户信息、无查询参数的 HTTPS URL。两次请求都通过 `X-Codex-Watch-Token` 请求头认证。下载完成后校验 SHA-256，随后使用 Android `PackageInstaller` 打开系统确认界面；应用不会尝试静默安装。

## 宠物素材与动作

应用始终提供内置代码占位宠物，并自动加载 `assets/pets/` 中的宠物包。当前随应用打包 `486`、`kabi`、`yukino` 和 `uniform-yukino`；四套资源均已通过图集校验，后两个为 v2 动作表。

要添加自己的宠物，请创建：

```text
app\src\main\assets\pets\YOUR_PET_ID\
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
  "spriteVersionNumber": 2,
  "spritesheetPath": "spritesheet.webp"
}
```

v2 spritesheet 为 `8 × 11` 网格，每格 `192 × 208`。互动页动作依次为 `IDLE`、`RUN RIGHT`、`RUN LEFT`、`WAVE`、`JUMP`、`FAILED`、`WAITING`、`WORKING`、`REVIEW`。

请只使用你拥有版权、已获得授权、或明确可公开分发的素材。

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
