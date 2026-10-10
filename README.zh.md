<div align="center">

<img src="docs/img/readme-cover.png" alt="Skerry — 桌面与 Android SSH 客户端，提供加密保险库和可选的自托管同步。" width="1200">

[English](README.md) · [Русский](README.ru.md) · **简体中文**

Skerry 是适用于 Linux、Windows、macOS 和 Android 的开源 SSH 客户端。
支持 SFTP、端口转发、VNC/RDP 和加密凭据保险库。

本地使用无需账号。可选的设备同步通过自托管服务器进行。

**[下载 Skerry](https://github.com/SeCherkasov/SkerrySSH/releases/latest)** · [使用指南](https://github.com/SeCherkasov/SkerrySSH/wiki) · [报告问题](https://github.com/SeCherkasov/SkerrySSH/issues/new/choose)

[![Release](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH?color=2fc8b4)](https://github.com/SeCherkasov/SkerrySSH/releases/latest)
[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-4b6475)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-4b6475)](server/LICENSE)

[功能](#features) · [截图](#screenshots) · [安装](#install) · [隐私](#privacy) · [开发](#build)

</div>

<a id="features"></a>

## 功能

| 组件 | 功能 |
| :--- | :--- |
| **连接** | SSH、Mosh、跳板机和 SSH 配置导入。 |
| **终端** | 每个标签页最多四个面板、同步输入、输出搜索和会话录制。 |
| **SFTP** | 双面板文件管理器、文件编辑器和传输队列。 |
| **隧道** | 本地、远程及 SOCKS 转发，支持保存配置。 |
| **远程桌面** | VNC 和 RDP，支持剪贴板交换。 |
| **运维** | 指标、告警、Docker/Kubernetes shell、命令片段和操作手册。 |
| **生产环境保护** | 在标记为 `prod` 的主机上执行高风险命令前进行确认。 |
| **协作** | 加密共享主机、命令片段和操作手册；实时终端共享。 |

<details>
<summary>其他功能</summary>

- **连接：** SSH 证书、键盘交互式认证、Telnet 和串口；从 `~/.ssh/config` 导入配置。
- **终端：** 命令历史、输入广播，以及 asciinema v2 录制和回放。
- **文件与隧道：** 文件查看、按名称筛选和一键转发发现的端口。
- **会话：** 可在连接期间调整 VNC/RDP 设置；共享终端支持观看或移交控制。
- **操作手册：** 按顺序执行命令及文件传输步骤。

</details>

<a id="screenshots"></a>

## 截图

<table>
<tr>
<td width="50%" align="center"><strong>分屏终端</strong><br><br><a href="docs/screenshots/panes.webp"><img src="docs/screenshots/panes.webp" alt="四个终端面板及同步输入" width="460"></a></td>
<td width="50%" align="center"><strong>SFTP 文件管理器</strong><br><br><a href="docs/screenshots/sftp.webp"><img src="docs/screenshots/sftp.webp" alt="SFTP 文件管理器中的本地和远程文件" width="460"></a></td>
</tr>
<tr>
<td width="50%" align="center"><strong>加密保险库</strong><br><br><a href="docs/screenshots/vault.webp"><img src="docs/screenshots/vault.webp" alt="密钥、密码和证书保险库" width="460"></a></td>
<td width="50%" align="center"><strong>操作手册</strong><br><br><a href="docs/screenshots/runbooks.webp"><img src="docs/screenshots/runbooks.webp" alt="包含命令和 SFTP 传输步骤的操作手册" width="460"></a></td>
</tr>
</table>

*点击图片查看完整尺寸。*

### Android

Android 界面提供终端附加按键栏和保险库生物识别解锁。

<p align="center">
  <img src="docs/screenshots/mobile-hosts.webp" alt="Android 主机分组与标签" width="220">
  &nbsp;&nbsp;
  <img src="docs/screenshots/mobile-terminal.webp" alt="Android 终端及附加按键栏" width="220">
</p>

深色、浅色和跟随系统的主题。界面支持 **英语、俄语、简体中文、土耳其语和德语**。

<details>
<summary>更多界面 — 终端、隧道、命令片段、AI 和团队</summary>

#### 终端

![终端](docs/screenshots/terminal.webp)

#### 端口转发

![端口转发](docs/screenshots/tunnels.webp)

#### 命令片段

![命令片段](docs/screenshots/snippets.webp)

#### AI 设置

![AI 设置](docs/screenshots/ai.webp)

#### 团队

![团队](docs/screenshots/teams.webp)

</details>

*封面是基于应用界面的产品展示插图。截图使用示例主机和演示数据。*

<a id="install"></a>

## 安装 Skerry

从 **[最新发布](https://github.com/SeCherkasov/SkerrySSH/releases/latest)** 下载适合平台的安装包。
Skerry 正在积极开发，目前仍处于 **1.0 之前的版本阶段**。

| 平台 | 架构 | 安装包 |
| :--- | :--- | :--- |
| Linux | x86_64 · arm64 | `.deb` · `.rpm` · `.AppImage` |
| Windows | x64 | `.msi` · `.zip` |
| macOS | Apple Silicon · Intel | `.dmg` |
| Android 8.0+ | arm64-v8a | `.apk` |

Windows ZIP 压缩包为便携版。

1. 安装 Skerry，使用主密码创建本地保险库。
2. 添加主机和凭据，或在桌面端导入 SSH 配置。
3. 打开会话，在终端旁使用文件管理、监控和端口转发。

请妥善保管主密码：**无法恢复丢失的主密码**。

<details>
<summary>安装说明 — 签名、校验和与 macOS 版本号</summary>

桌面安装包目前未签名，macOS 构建也未经过公证。Windows SmartScreen
或 macOS Gatekeeper 可能在首次启动时要求允许应用运行。在 macOS 上，
可通过 System Settings → Privacy & Security 允许下载的应用。

发布中包含 `SHA256SUMS.txt`。在 Linux 上，进入下载目录后验证安装包：

```bash
sha256sum -c --ignore-missing SHA256SUMS.txt
```

由于打包要求，macOS Get Info 显示的应用包版本为 `1.x.y`。
Skerry 的实际版本显示在应用内的 About 页面。

</details>

Linux、Windows、macOS 和 Android 正在积极开发。iOS/iPadOS 支持已暂缓，项目目前没有 iOS 构建目标。

<a id="privacy"></a>

## 保险库与同步

- **本地保险库：** Argon2id 和 XChaCha20-Poly1305 保护密钥、密码、身份和证书。主密码及明文加密密钥保留在设备上。
- **可选同步：** 自托管服务器存储加密记录、封装的密钥和同步元数据。其设计目标是无法解密保险库内容。
- **设备与团队：** 二维码配对，以及端到端加密共享主机、命令片段和操作手册。

部署、TLS 和备份请参阅 [同步服务器指南](server/README.zh.md)。
在不可信网络上使用 HTTPS；服务器需要 TLS 反向代理。

**密码学实现尚未经过独立审计。** 限制和私密漏洞报告方式见
[安全策略与威胁模型](SECURITY.md)。

## AI 与隐私

AI 支持通过 llama.cpp 运行本地 GGUF 模型，也支持使用自己的 API 密钥连接 OpenAI 兼容端点。
**默认主机策略仅允许本地模型。** 主机策略允许云端请求时，请求直接发送到配置的提供方。

**会话助手默认附带最近两条命令的输出。** 将上下文选择器设为零即可不发送这些输出。
执行建议的命令需要确认；高风险命令还需要额外确认。

<details>
<summary>主机策略与发送给模型的数据</summary>

| 策略 | 行为 |
| :--- | :--- |
| **Strict** · 默认 | 仅使用本地模型，并脱敏可识别的秘密信息。 |
| **Balanced** | 允许云端请求，发送前脱敏可识别的秘密信息。 |
| **Permissive** | 允许云端请求，不做脱敏。 |
| **Off** | 在此主机上禁用 AI。 |

- **会话助手：** 问题、对话历史及选定数量的最近命令输出。
- **Explain：** 选中的输出或上一条命令的输出块。
- **主机列表和保险库记录：** 不会自动附带。
- **脱敏：** 基于模式匹配，并不提供保证。Quick-chat 始终脱敏可识别的秘密信息，包括本地模式。

</details>

<a id="build"></a>

## 开发

基于 **Kotlin Multiplatform、Compose Multiplatform 和 Ktor**。客户端构建需要 **JDK 21 和 Android SDK**，桌面端也不例外。设置 `ANDROID_HOME` 或在 `local.properties` 中设置 `sdk.dir`。

```bash
./gradlew :composeApp:run               # Desktop
./gradlew :androidApp:installDebug      # Android
./gradlew :server:run -PserverOnly      # Server
```

仅构建服务器时无需 Android SDK。请按 [服务器启动说明](server/README.zh.md#快速开始) 配置 `SKERRY_JWT_SECRET`。

[贡献指南](CONTRIBUTING.md) · [开发与验证](docs/development-process.md) · [依赖版本](gradle/libs.versions.toml) · [问题与功能建议](https://github.com/SeCherkasov/SkerrySSH/issues/new/choose)

## 许可证

客户端：**[GPL-3.0](LICENSE)**。同步服务器：**[AGPL-3.0](server/LICENSE)**。
内置字体许可证和署名见 [licenses/](licenses/README.md)。
