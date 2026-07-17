# Skerry 同步服务器

[English](README.md) · [Русский](README.ru.md) · **中文**

为 [Skerry](../README.zh.md) 设计的自托管、零知识端到端加密同步服务。
服务器**只存储密文**——加密后的 `dataKey` 和保险库记录——以及同步元数据。
主密码、`masterKey` 和 `dataKey` 从不离开设备，服务器无法获取。

> 许可证：**AGPL-3.0**（见 `LICENSE`）。客户端为 GPL-3.0。

## 技术栈

- **技术**：Kotlin + Ktor (Netty)，Exposed，HikariCP。认证：SRP-6a (Nimbus) + JWT。
- **存储**：默认 SQLite（单文件、零配置）；通过设置 `SKERRY_DB_URL` 切换到 PostgreSQL。
- **设计原则**：服务器不执行加密解密操作。注册仅上传 SRP 盐值/验证器和加密后的 `dataKey`；登录通过 SRP-6a 交换，密码本身从不传输。

## 快速启动

### Docker（预构建镜像，推荐）

多架构镜像（amd64 + arm64）发布至 Docker Hub：[`secherkasov/skerry-sync`](https://hub.docker.com/r/secherkasov/skerry-sync)。
标签：精确 `<版本>`、`<主.次>`、`latest`。服务器独立发布，与客户端版本无关。

```bash
docker run -d --name skerry-sync \
  -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

保持 `SKERRY_JWT_SECRET` 在容器重建时不变（存入 `.env` 文件），修改它会作废所有已颁发的令牌。

### Docker Compose（从源码构建）

```bash
# 从仓库根目录
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"
docker compose up -d --build
```

两种方式都会在 `http://localhost:8080` 启动服务。数据存储在 `skerry-data` 卷中（SQLite）。
切换到 PostgreSQL 只需取消 `docker-compose.yml` 中 `db` 服务和对应变量的注释。

容器以非特权用户运行，开放 `/healthz` 健康检查，以 `-PserverOnly` 构建镜像——无需 Android SDK。

### 本地运行 (Gradle)

```bash
SKERRY_JWT_SECRET=dev-secret SKERRY_ADMIN_TOKEN=admin ./gradlew :server:run -PserverOnly
```

## 配置

全部通过环境变量配置（单 `.env` 模型）；带注释的模板见 [`.env.example`](.env.example)。
所有变量对于本地运行都有合理默认值——生产环境仅需一个稳定的 `SKERRY_JWT_SECRET`。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SKERRY_HOST` | `0.0.0.0` | 绑定接口。反向代理后设为 `127.0.0.1`。 |
| `SKERRY_PORT` | `8080` | 监听端口。 |
| `SKERRY_DB_URL` | `jdbc:sqlite:skerry-sync.db` | JDBC 地址；`jdbc:postgresql://…` 切换到 PostgreSQL。 |
| `SKERRY_DB_USER` / `SKERRY_DB_PASSWORD` | *(空)* | 数据库凭据（PostgreSQL）。 |
| `SKERRY_JWT_SECRET` | `dev-insecure-change-me` | JWT 签名密钥。**使用默认值服务器拒绝启动**，除非 `SKERRY_DEV=1`。轮换会作废所有令牌。 |
| `SKERRY_JWT_ISSUER` | `skerry-sync` | JWT `iss` 声明。 |
| `SKERRY_ADMIN_TOKEN` | *(空)* | 管理控制台令牌（`/console`、`/admin/*`）。为空则管理端点关闭。 |
| `SKERRY_ACCESS_TTL` | `900` (15 分钟) | 访问令牌有效期，秒。 |
| `SKERRY_REFRESH_TTL` | `2592000` (30 天) | 刷新令牌有效期，秒。 |
| `SKERRY_PAIRING_TTL` | `300` (5 分钟) | 二维码配对会话有效期。 |
| `SKERRY_TOMBSTONE_DAYS` | `90` | 删除记录保留天数，超期物理清理。 |
| `SKERRY_CORS_HOSTS` | *(空)* | 逗号分隔的允许 CORS 来源。空则禁用 CORS（原生客户端不受同源限制）。 |
| `SKERRY_MAX_BODY_BYTES` | `4194304` (4 MiB) | 请求体上限；超限返回 `413`。 |
| `SKERRY_DEV` | *(未设置)* | `1` 允许本地开发使用默认 JWT 密钥。 |
| `SKERRY_MAIL_ENABLED` | `false` | 启用邮件通知。 |
| `SKERRY_SMTP_HOST` | *(空)* | SMTP 服务器地址。 |
| `SKERRY_SMTP_PORT` | `587` | SMTP 端口。 |
| `SKERRY_SMTP_USER` | *(空)* | SMTP 用户名。 |
| `SKERRY_SMTP_PASSWORD` | *(空)* | SMTP 密码。 |
| `SKERRY_SMTP_FROM` | `Skerry <noreply@sync.onepve.com>` | 发件人地址。 |
| `SKERRY_SMTP_TLS` | `true` | 使用 STARTTLS（587）或 SMTPS（465）。 |
| `SKERRY_MAIL_CONFIG_PATH` | `./data/mail-config.json` | 邮件模板配置文件路径。 |

## 同步原理

1. **注册** — 客户端本地派生密钥（Argon2id → `masterKey` → `authKey`/`dataKey`），上传 SRP 盐值和验证器，以及用主密钥加密后的 `dataKey`。上传内容不足以解密任何数据。
2. **登录** — SRP-6a 认证交换；服务器仅得知客户端知道密码这一事实，密码本身永不可见。成功后签发短期访问令牌 + 刷新 JWT。
3. **推送/拉取** — 客户端 `PUT` 加密记录批次；冲突按最后写入者胜出（记录 `version`，然后 `deviceId` 决胜）。拉取按单调光标增量（`?since=`）。
4. **实时更新** — `/sync` WebSocket 推送"有变更"信号（仅含新光标，不含内容），客户端再拉取增量。
5. **删除** — 以墓碑记录传播，超 `SKERRY_TOMBSTONE_DAYS` 后物理清除。
6. **新设备** — 登录后从 `/vault/keys` 获取加密的 `dataKey`，或使用 QR 快速配对（`/pairing/*`，短 TTL 一次性会话）。

所有加密数据 (`blob`、`wrappedDataKey`、`encryptedDataKey`) 均以 base64 传输。

## API

### 健康与认证

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/healthz` | 存活检测（公开，容器健康检查用）。 |
| `POST` | `/auth/register` | 注册：SRP 盐值/验证器 + 加密 dataKey → 令牌。 |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | SRP-6a 登录，密码不传输。 |
| `POST` | `/auth/refresh` | 令牌轮换。 |

### 保险库与设备 (JWT 认证)

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/vault/keys` | 获取加密的 `dataKey`（新设备用）。 |
| `GET` | `/vault/records?since={cursor}` | 增量拉取加密记录。 |
| `PUT` | `/vault/records` | 批量写入（LWW：version 优先，deviceId 决胜）。 |
| `WS` | `/sync` | "有变更"通知（仅光标，不含内容）。 |
| `GET` / `DELETE` | `/devices`、`/devices/{id}` | 设备列表和撤销。 |
| `POST` | `/pairing/start` (认证后) → `/pairing/claim` | 快速二维码配对。 |

### 团队 (JWT 认证)

端到端加密共享：团队记录对服务器为密文，成员加入通过密封信封邀请（依赖成员公钥）。

| 方法 | 路径 | 用途 |
|---|---|---|
| `PUT` | `/account/key` | 发布账户公钥。 |
| `GET` | `/account/keys/{accountId}` | 获取成员公钥（用于信封）。 |
| `POST` / `GET` / `DELETE` | `/teams`、`/teams/{id}` | 创建、列出、删除团队。 |
| `GET` / `POST` | `/teams/{id}/members` | 成员列表；邀请（密封信封）。 |
| `PUT` | `/teams/{id}/members/{accountId}/role` | 更改角色（owner/member）。 |
| `DELETE` | `/teams/{id}/members/{accountId}` | 移除成员 / 撤销访问。 |
| `POST` | `/teams/{id}/accept` | 接受邀请。 |
| `GET` / `PUT` | `/teams/{id}/records` | 拉取/推送加密的共享记录。 |
| `GET` | `/teams/{id}/activity` | 团队活动动态。 |

### 管理 (需 `SKERRY_ADMIN_TOKEN`，Header `X-Admin-Token`)

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/admin/health` | 存活检测（公开）。 |
| `GET` | `/admin/stats` | 聚合统计：账户数、设备数、记录数、密文大小。 |
| `GET` | `/admin/devices` | 全部设备，含平台、光标、最后同步。 |
| `GET` | `/admin/activity` | 审计日志（最近 2000 条）。 |
| `GET` | `/admin/accounts`、`/admin/accounts/{id}/records` | 账户列表、单账户记录元数据。 |
| `DELETE` | `/admin/devices/{id}?accountId=` | 从控制台撤销设备。 |
| `DELETE` | `/admin/accounts/{id}/tombstones` | 提前清除账户墓碑。 |
| `DELETE` | `/admin/accounts/{id}` | 删除账户及其全部数据。 |

## 管理控制台

静态页面位于 `http://localhost:8080/console`（需要 `SKERRY_ADMIN_TOKEN`）——
统一仪表板：**概览**（账户、设备、记录、密文总量）、**设备**（平台、最后同步、光标版本、状态 + 撤销）、**隐私边界**（服务器能看见和不能看见什么）、**最近动态**（审计日志）。零知识原则：控制台仅可见元数据——事件、设备、平台标签、光标和体积统计——永远看不到记录内容、主密码或 `dataKey`。

> 字体（Space Grotesk、JetBrains Mono）内置于服务器（`resources/admin/fonts/*.woff2`），图标为内联 SVG，控制台完全离线运行，无外部 CDN 请求。

> ⚠️ 元数据包含 `accountId`（即邮箱），并保留在审计日志中（最近 2000 条）。对于单人自托管场景，运维者就是数据主体——可接受。管理令牌通过 `X-Admin-Token` 头明文传输：请在前端加 TLS 终端（见下文），否则链路上令牌可见。

## 生产安全

- 设置稳定的 `SKERRY_JWT_SECRET`（否则重启作废所有令牌）和非空的 `SKERRY_ADMIN_TOKEN`。
- 备份 = SQLite 文件 (`/data`) 或 PostgreSQL 导出。数据已加密，但这是你唯一的恢复路径。
- 服务器本身监听明文 HTTP——TLS 由反向代理处理（见下）。通信内容本身为端到端加密（零知识），SRP 在明文中也安全，但**管理令牌和元数据（包括 `accountId` = 邮箱）在明文中传输**——无 TLS 时链路上可见。公开可达的主机必须加 TLS。

### TLS 终止

客户端指向 `https://…` 后，`/sync` WebSocket 自动切换为 `wss://`。

**Caddy**（自动 Let's Encrypt，最简单）：

```caddy
sync.example.com {
    reverse_proxy localhost:8080
}
```

**nginx**（自己的证书或 Certbot；必须转发 `/sync` 的 WebSocket 升级）：

```nginx
server {
    listen 443 ssl;
    server_name sync.example.com;
    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h;
    }
}
```

将服务器绑定至本地回环 (`SKERRY_HOST=127.0.0.1`) 使 8080 端口不被外部直接访问。

> **在受信 LAN 中无 TLS 自托管**是可接受的刻意选择：通信内容端到端加密，元数据留在内网。Android 客户端允许明文（`network_security_config.xml`）。一旦主机变为外部可达——必须加 TLS。

## 测试

```bash
./gradlew :server:test
```

覆盖 LWW 冲突、SRP 往返、JWT、团队角色/ACL，以及完整 HTTP 流程（注册 → 登录 → 推送/拉取 → 设备 → 配对 → 管理）。
