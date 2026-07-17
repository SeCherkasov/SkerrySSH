# Skerry 邮局功能方案

## 一、架构总览

```
client (KMP)                          server (Ktor)
─────────                             ─────────────
  注册时强制 email 格式                 POST /auth/register → 发欢迎邮件
  表单改为 email 键盘类型               POST /pairing/claim → 发新设备告警
                                       POST /vault/change-password → 发密码变更通知
                                       POST /auth/srp/verify → 检查IP → 可疑登录告警
                                          │
                                          ▼
                                    EmailService (SMTP)
                                          │
                                          ▼
                                    EmailTemplates (zh/en/ru)
```

## 二、服务端新增文件

### 1. `server/.../mail/EmailService.kt`
- SMTP 连接池（javax.mail / Jakarta Mail）
- `sendEmail(to, subject, htmlBody)` 方法
- 异步发送、失败重试、日志

### 2. `server/.../mail/EmailTemplates.kt`
- 4 个模板函数，各 3 语言：
  - `welcomeEmail(accountId, lang)` — 注册欢迎
  - `newDeviceEmail(accountId, deviceName, platform, lang)` — 新设备配对告警
  - `passwordChangedEmail(accountId, deviceName, lang)` — 主密码变更通知
  - `suspiciousLoginEmail(accountId, deviceName, ip, lang)` — 可疑登录告警

### 3. `ServerConfig.kt` 新字段
```kotlin
val smtpHost: String,          // SKERRY_SMTP_HOST
val smtpPort: Int,             // SKERRY_SMTP_PORT (587/465)
val smtpUser: String,          // SKERRY_SMTP_USER
val smtpPassword: String,      // SKERRY_SMTP_PASSWORD
val smtpFrom: String,          // SKERRY_SMTP_FROM (noreply@sync.onepve.com)
val smtpTls: Boolean,          // SKERRY_SMTP_TLS (STARTTLS)
val mailEnabled: Boolean,      // SKERRY_MAIL_ENABLED (缺省关闭)
```

### 4. `Services.kt` 注入
```kotlin
val mail = EmailService(config)
```

## 三、触发点（Hook）

### 事件 1：注册欢迎 ─ `AuthRoutes.kt:55`
```kotlin
services.accounts.create(...)        // 现有
services.devices.register(...)       // 现有
services.activity.record(...)        // 现有
services.mail.sendWelcome(accountId) // ← 新增，异步 fire-and-forget
```

### 事件 2：新设备配对 ─ `PairingRoutes.kt:67`
```kotlin
services.devices.register(session.accountId, req.deviceId, req.deviceName)
services.mail.sendNewDeviceAlert(session.accountId, req.deviceName, req.platform) // ← 新增
```

### 事件 3：密码变更 ─ 找 `VaultRoutes.kt` 中 change-master-password 端点
```kotlin
services.mail.sendPasswordChanged(accountId, deviceName)  // ← 新增
```

### 事件 4：可疑登录 ─ `AuthRoutes.kt:112`（SRP verify 成功）
- 比较当前请求 IP 与上次登录 IP
- 设备表加 `last_ip` 字段（varchar 45，存字符串）
- IP 不匹配时发告警，其余正常登录不发

## 四、数据库变更

`devices` 表加 1 列：
```sql
ALTER TABLE devices ADD COLUMN last_ip VARCHAR(45);  -- IPv6 最长 45 字符
```

## 五、客户端改动

### `SyncSetupForm.kt`
```kotlin
fun canSubmit(passwordLength: Int): Boolean =
    passwordLength > 0 
    && isAccountEmail                          // ← 新增：必须邮箱格式
    && isHttpUrl(normalizedServerUrl)
```

### 表单 UI
- `SyncSetupBody` / `SyncSetupDialog` 中：
  - `account` 字段 `KeyboardType.Email`
  - 当前是黄字警告 → 改为**红色错误 + 阻止提交**
  - 提交按钮 disabled 时加上 tooltip "请输入有效的邮箱地址"

## 六、邮件模板（3 语言）

```html
<!-- 注册欢迎 -->
logo: Skerry 图标
标题: 「欢迎加入 Skerry」/「Welcome to Skerry」/「Добро пожаловать в Skerry」
正文: 你的账户 xxx@xxx 已创建。端到端加密保障隐私。
副文: 下载客户端 → 链接

<!-- 新设备告警 -->
标题: 「⚡ 新设备已关联」/「⚡ New Device Linked」/「⚡ Подключено новое устройство」
正文: 设备「xxx」(Android/Linux/Windows/macOS) 于 <时间> 关联了你的保险库。
警告: 如非本人操作，请立即登录管理控制台撤销该设备。

<!-- 密码变更通知 -->
标题: 「🔐 主密码已变更」/「🔐 Master Password Changed」/「🔐 Мастер-пароль изменён」
正文: 设备「xxx」于 <时间> 更改了主密码。

<!-- 可疑登录告警 -->
标题: 「🚨 可疑登录」/「🚨 Suspicious Login」/「🚨 Подозрительный вход」
正文: 你的账户于 <时间> 从新 IP (<ip>) 登录，设备「xxx」。
```

## 七、环境变量

```
SKERRY_MAIL_ENABLED=true
SKERRY_SMTP_HOST=smtp.example.com
SKERRY_SMTP_PORT=587
SKERRY_SMTP_USER=noreply@sync.onepve.com
SKERRY_SMTP_PASSWORD=xxx
SKERRY_SMTP_FROM="Skerry <noreply@sync.onepve.com>"
SKERRY_SMTP_TLS=true
```

## 八、实施步骤

| 步骤 | 内容 | 预计改动 |
|------|------|---------|
| 1 | `devices` 表加 `last_ip` 列 + migration | 1 文件 |
| 2 | `ServerConfig` 加 SMTP 字段 | 1 文件 |
| 3 | `EmailService` + `EmailTemplates` | 2 新文件 |
| 4 | 4 个路由 Hook 注入邮件调用 | 3 文件 |
| 5 | `Services` 注入 `mail` | 1 文件 |
| 6 | 客户端 `canSubmit` 强制邮箱 | 1 文件 |
| 7 | 客户端 UI：红字错误替黄字警告 | 2 文件 |
| 8 | Docker 环境变量更新 | 1 文件 |
