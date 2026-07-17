# Skerry — 功能详情

[README](../README.zh.md) 为高级概述，本文档为完整功能说明。

## 连接

- SSH（sshj + BouncyCastle），支持 SSH 证书
- SSH 跳板（ProxyJump），连接信息面板显示跳板路由
- 按主机配置心跳间隔（关闭 / 30 / 60 / 120 秒），含死链检测
- SFTP（双窗格文件管理），路径栏支持输入跳转
- 端口转发：本地（`-L`）、远程（`-R`）、动态/SOCKS（`-D`）
- Mosh：原生客户端实现（AES-128-OCB 数据报，状态同步协议）——会话在网络中断、漫游和休眠后存活；`mosh-server` 通过当前配置的 SSH 认证启动（含跳板），远程主机需安装 `mosh` 包并开放 UDP 60000–61000 端口；输入错误时会逐项说明服务器端要求
- Telnet（自定义 IAC 协商编解码器）
- 串口：桌面端使用 jSerialComm；Android 端支持 USB-OTG（CDC/FTDI/CP210x/CH34x 芯片）

## 终端

- 自研网格仿真：VT 线条绘制、Unicode/组合字符、SGR、OSC 8/4/52/104、括号粘贴
- 会话标签支持分屏、SSH 自动重连、拖拽排序
- 状态栏实时主机延迟（RTT）
- JetBrains Mono 等宽字体渲染，回滚搜索
- 可点击 URL（OSC 8 超链接和裸 URL）

## 保险库

- 始终加密：Argon2id 密钥派生，XChaCha20-Poly1305（libsodium）；零知识——主密码从不离开设备
- 生物识别解锁（BiometricPrompt），包含重置/恢复流程，Android 上启用 `FLAG_SECURE`
- 管理密钥、密码、身份、证书

## 同步（自托管，可选）

- 零知识同步：authKey/dataKey 分钥，XChaCha20-Poly1305 载荷，SRP-6a 认证（服务器仅存验证器，密码永不可见），JWT 会话
- 实时同步：WebSocket 推送变更，墓碑传播，光标持久化，按记录类型选择性同步
- 设备通过二维码配对（ZXing + CameraX + ML Kit，设备端处理），管理控制台
- 部署详见 [server/README.zh.md](../server/README.zh.md)

## 团队（共享，可选）

- 端到端零知识共享主机和代码片段，基于密封信封邀请；owner/member 角色，ACL 撤销

## 代码片段与 AI

- 命令库，终端中支持输入补全
- AI 助手（BYOK OpenAI，按主机策略：严格/平衡/宽松/关闭），SSE 流式输出；云端请求前脱敏密钥——详见 [AI 与隐私](../README.zh.md#ai-与隐私)
- 设备本地 AI：应用自动下载 GGUF 模型并通过 llama.cpp 本地运行（模型目录：Qwen3、Phi-4 Mini）——严格策略完全离线工作
- 建议命令从不自动执行；风险分级对危险命令额外要求二次确认

## 多语言

- 字符串存于 compose-resources（`composeApp/src/commonMain/composeResources/values*`）；语言切换器（`LocalAppLocale`）同时驱动 UI 和 AI 助手的回复语言（INFO/ASK）
- 语言：中文、English、Русский
