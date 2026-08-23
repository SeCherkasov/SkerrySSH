# Skerry 同步服务器

[English](README.md) · [Русский](README.ru.md) · **简体中文**

为 [Skerry](../README.zh.md) 提供自托管、零知识的端到端同步（Vaultwarden 模型）。服务器
**只存储密文** — 被包装的 `dataKey` 和加密后的保险库记录 — 外加同步元数据。主密码、
`masterKey` 和 `dataKey` 从不离开设备，服务器无从获取。

> 许可证：**AGPL-3.0**（见 `LICENSE`）。Skerry 客户端是 GPL-3.0。

## 内容概览

- **技术栈**：Kotlin + Ktor（Netty）、Exposed、HikariCP。认证：SRP-6a（Nimbus）+ JWT。
- **存储**：默认 SQLite（单个文件，零配置）；改 `SKERRY_DB_URL` 即可切到 PostgreSQL。
- **服务器上不做加解密**，这是设计使然：服务器无法解密用户数据。注册时上传 SRP
  salt/verifier 和被包装的 `dataKey`；登录是一次 SRP-6a 交换，密码本身从不传输。

## 快速开始

### Docker（预构建镜像，推荐）

多架构镜像（amd64 + arm64）发布在 Docker Hub 上，仓库为
[`secherkasov/skerry-sync`](https://hub.docker.com/r/secherkasov/skerry-sync) — 标签有：
精确的 `<version>`、`<major.minor>`、`latest`。服务器与客户端分开发布 — 有自己的
`server-v*` 标签和自己的工作流。目前为止它的版本号与客户端发布保持一致。

```bash
docker run -d --name skerry-sync \
  -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

重建容器时要保持 `SKERRY_JWT_SECRET` 不变（把它放进 env 文件）— 改动它会让所有已签发的
令牌失效。

### Docker Compose（从源码构建）

```bash
# 在仓库根目录执行
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"
docker compose up -d --build
```

两种方式都会让服务器起在 `http://localhost:8080`。数据放在 `skerry-data` 卷里（SQLite）。
要换成 PostgreSQL，取消 `docker-compose.yml` 中 `db` 服务和 postgres 变量的注释。

容器以非特权用户运行，提供 `/healthz` 健康检查，镜像使用 `-PserverOnly` 构建 — 不需要
Android SDK。管理 CLI 随镜像一同提供：`docker exec skerry-sync skerry-admin --help`。

### 本地运行（Gradle）

```bash
SKERRY_JWT_SECRET=dev-secret SKERRY_ADMIN_TOKEN=admin ./gradlew :server:run -PserverOnly
```

## 配置

一切都通过环境变量配置（单一 `.env` 模型）；带注释的模板见
[`.env.example`](.env.example)。所有取值对本地运行都有合理的默认值 — 生产环境唯一
*必须*设置的是稳定的 `SKERRY_JWT_SECRET`。

| 变量 | 默认值 | 用途 |
|---|---|---|
| `SKERRY_HOST` | `0.0.0.0` | 绑定的网络接口。在反向代理之后设为 `127.0.0.1`。 |
| `SKERRY_PORT` | `8080` | 监听端口。 |
| `SKERRY_DB_URL` | `jdbc:sqlite:skerry-sync.db` | JDBC URL；`jdbc:postgresql://…` 会把驱动切到 PostgreSQL。 |
| `SKERRY_DB_USER` / `SKERRY_DB_PASSWORD` | *(空)* | 数据库凭据（PostgreSQL）。 |
| `SKERRY_JWT_SECRET` | `dev-insecure-change-me` | JWT 签名密钥。**除非设置 `SKERRY_DEV=1`，否则服务器拒绝以默认值启动。** 轮换它会让所有已签发的令牌失效。 |
| `SKERRY_JWT_ISSUER` | `skerry-sync` | JWT 的 `iss` 声明。 |
| `SKERRY_ADMIN_TOKEN` | *(空)* | 运维控制台令牌（`/console`、`/admin/*`）。为空 ⇒ 管理数据端点关闭。 |
| `SKERRY_ACCESS_TTL` | `900`（15 分钟） | access 令牌有效期，单位秒。 |
| `SKERRY_REFRESH_TTL` | `2592000`（30 天） | refresh 令牌有效期，单位秒。 |
| `SKERRY_PAIRING_TTL` | `300`（5 分钟） | 一次性扫码配对会话的有效期。 |
| `SKERRY_TOMBSTONE_DAYS` | `90` | 删除墓碑在被物理清理前保留多久。 |
| `SKERRY_CORS_HOSTS` | *(空)* | 逗号分隔的允许 CORS 来源。为空则禁用 CORS（原生客户端不受其约束）。 |
| `SKERRY_MAX_BODY_BYTES` | `4194304`（4 MiB） | 请求体上限（防 OOM/滥用）；更大的请求会得到 `413`。 |
| `SKERRY_DEV` | *(未设置)* | `1` 解锁默认 JWT 密钥，仅用于本地开发。 |
| `SKERRY_METRICS` | `off` | Prometheus `/metrics`：`off`（404）、`token`（bearer）、`open`（无凭据）。 |
| `SKERRY_METRICS_TOKEN` | *(空)* | `SKERRY_METRICS=token` 时使用的 bearer 令牌。模式为 `token` 而此项为空时启动失败。 |
| `SKERRY_METRICS_INVENTORY_SECONDS` | `60` | 存量指标的刷新间隔（最小 15，`0` 表示禁用）。 |

## 同步是怎么工作的

1. **注册** — 客户端在本地派生密钥（Argon2id → `masterKey` → `authKey`/`dataKey`），
   上传 SRP salt/verifier 以及用主密钥包装过的 `dataKey`。上传的任何内容都不足以解密
   任何东西。
2. **登录** — SRP-6a 的 challenge/verify；服务器只得知客户端知道密码，而永远得不到密码
   本身。成功后签发短期的 access + refresh JWT。
3. **推送/拉取** — 客户端 `PUT` 一批加密记录；冲突按后写者胜出解决（先看记录 `version`，
   再用 `deviceId` 决胜）。拉取是按单调游标（`?since=`）取增量。
4. **实时更新** — `/sync` WebSocket 推送“有变更”的信号，只携带新游标，绝不携带内容；
   客户端随后自行拉取增量。
5. **删除** — 以墓碑形式传播，超过 `SKERRY_TOMBSTONE_DAYS` 后被物理清除。
6. **新设备** — 要么登录后从 `/vault/keys` 取回被包装的 `dataKey`，要么使用快速扫码配对
   （`/pairing/*`，一次性会话，TTL 很短）。

所有密文块（`blob`、`wrappedDataKey`、`encryptedDataKey`）都以 base64 传输。

## API

### 健康检查与认证

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/healthz` | 存活探针（公开；容器健康检查使用）。从不触碰数据库。 |
| `GET` | `/readyz` | 就绪探针：`200` + `{"status":"ready","db":"up"}`；数据库探测连续失败三次时返回 `503`。 |
| `GET` | `/metrics` | Prometheus 指标输出。默认关闭 — 见 `SKERRY_METRICS`。 |
| `POST` | `/auth/register` | 注册：SRP salt/verifier + 被包装的 dataKey → 令牌。 |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | SRP-6a 登录，不传输密码。 |
| `POST` | `/auth/refresh` | access/refresh 令牌轮换。 |
| `POST` | `/auth/change-password` | 更换密码：用当前密码做 SRP 证明，提交新的 verifier 和重新包装的 dataKey。 |
| `GET` | `/auth/web-password`（JWT） | 账号是否设置了 Web 密码 — 应用中“Web 访问”卡片读取的就是它。 |
| `POST` | `/auth/web-password`（JWT） | 从应用设置、轮换或清除 **Web** 密码。清除同时会吊销已打开的浏览器会话。 |
| `POST` | `/auth/web-login` | 用 Web 密码换取标准令牌对。有速率限制；该会话注册为 `platform = "web"` 的设备。 |

### 保险库与设备（JWT 认证）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/vault/keys` | 供新设备取用的被包装 `dataKey`。 |
| `GET` | `/vault/records?since={cursor}` | 加密记录的增量。 |
| `GET` | `/vault/envelopes` | 记录元数据和一小段密文预览 — 只有尺寸，绝不含 blob（账号区域的“存储”一节）。 |
| `GET` | `/account/summary` | 调用者自己的汇总：设备、记录、墓碑、密文大小、最后同步时间。 |
| `GET` | `/account/activity` | 调用者自己的审计记录（不含团队范围内的条目）。 |
| `PUT` | `/vault/records` | 批量 upsert，采用 LWW（先 version，再 deviceId）。 |
| `WS` | `/sync` | “有变更”推送（只有游标，没有内容）。 |
| `GET` / `DELETE` | `/devices`、`/devices/{id}` | 设备列表与吊销。 |
| `POST` | `/pairing/start`（需认证）→ `/pairing/claim` | 本地快速扫码配对。 |

### 团队（JWT 认证）

端到端加密的共享：团队记录对服务器而言就是密文，成员资格通过针对成员公钥的密封信封
邀请来授予。

| 方法 | 路径 | 用途 |
|---|---|---|
| `PUT` | `/account/key` | 发布账号公钥。 |
| `GET` | `/account/keys/{accountId}` | 取某个成员的公钥（用于封装信封）。 |
| `POST` / `GET` / `DELETE` | `/teams`、`/teams/{id}` | 创建、列出、删除团队。 |
| `GET` / `POST` | `/teams/{id}/members` | 成员列表；邀请（密封信封）。 |
| `PUT` | `/teams/{id}/members/{accountId}/role` | 修改角色（owner/member）。 |
| `DELETE` | `/teams/{id}/members/{accountId}` | 移除成员／吊销访问权。 |
| `POST` | `/teams/{id}/accept` | 接受邀请。 |
| `GET` / `PUT` | `/teams/{id}/records` | 拉取/推送加密的共享记录。 |
| `GET` | `/teams/{id}/activity` | 团队活动流。 |

### 管理接口（受 `SKERRY_ADMIN_TOKEN` 保护，请求头 `X-Admin-Token`）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/admin/health` | 存活探针（公开）。 |
| `GET` | `/admin/stats` | 汇总：账号、设备、记录、blob 大小。 |
| `GET` | `/admin/devices` | 全部设备，含平台、游标、最后同步时间。 |
| `GET` | `/admin/activity` | 审计日志（最近 2000 条事件）。 |
| `GET` | `/admin/accounts`、`/admin/accounts/{id}/records` | 账号列表，以及按账号列出的记录元数据。 |
| `DELETE` | `/admin/devices/{id}?accountId=` | 从控制台吊销一台设备。 |
| `DELETE` | `/admin/accounts/{id}/tombstones` | 提前清除某账号的墓碑。 |
| `DELETE` | `/admin/accounts/{id}` | 删除账号及其全部数据。 |

### 删除账号

`DELETE /admin/accounts/{id}` 在一个事务里删掉该账号拥有的一切：记录、设备、配对会话、
它发布的 Teams 公钥、它的成员资格和范围授权 — 不会留下任何指向已不存在 id 的行
（SQLite 不强制外键，PostgreSQL 则会在还有残留时直接拒绝删除）。该账号**拥有**的团队会
转交给资历最深的活跃成员，由其成为新 owner；若没有活跃成员，团队连同其全部记录、范围和
授权一并删除。审计记录会写明每个受影响的团队及其新 owner，该团队自己的活动流里会落一条
`team.owner_replaced`，其余每位成员都会收到与任何成员变更相同的实时信号。之后剩余成员应
轮换团队密钥 — 被删除的账号知道它，而只有客户端才能轮换。

这之后没有任何机制阻止同一个人重新注册同一个 id：id 是确定性的，本地保险库还在他们的
设备上，因此一次新的注册会把它重新上传。若这一点很重要，请把实例关闭注册
（`SKERRY_REGISTRATION=closed`）。

## Web 前端

一份静态产物，三个入口，由 Ktor 自己提供服务：

| URL | 面向谁 | 凭据 | 显示什么 |
|---|---|---|---|
| `/` | 任何人 | 无 | 实例是否在服务、版本是多少、是否开放注册，以及粘贴到客户端里的 URL。 |
| `/account` | 账号所有者 | 账号 id + **Web 密码**（在应用中设置） | 设备、团队、实时会话、已存记录的信封、账号自己的日志、安全相关的交接。 |
| `/console` | 运维人员 | `SKERRY_ADMIN_TOKEN` | 实例总量、账号（设备和记录信封在行内展开）、可观测性、审计日志。 |

Web 密码是一个独立凭据，与任何保险库密钥无关：索取它的页面由它所保护的同一台服务器提供，
因此主密码绝不会经由这条路径传输。用它登录的浏览器只能读取服务器本就以明文持有的元数据，
无法解密任何记录 — `dataKey` 不在这条流程里。它拿到的令牌在服务端就被限定为只读，
不含 `/vault/keys` 和 `/vault/records`，另外允许吊销设备。丢了这个密码的代价是从应用里
重置一次；丢了主密码则依然是设计上不可恢复的。

密码在应用中设置：**设置 → 同步 → Web 访问**，需在一台已连接的设备上操作。同一张卡片可以
轮换和撤销它 — 撤销的同时也会让当时打开的浏览器会话退出登录。长度为 8–256 个字符。
卡片背后的端点是 `POST /auth/web-password`（`{"password": null}` 表示清除），脚本也可以
这样调用。

账号的令牌对存放在 `sessionStorage` 里，随标签页关闭而消失；管理令牌只保存在内存中，
页面刷新后会再次索取。每一个破坏性操作（吊销、清除、删除）在执行前都会说明它确切的
影响范围。

团队成员资格和密钥**不能**在浏览器里管理：邀请或密钥轮换要用团队密钥密封一个信封，而
任何浏览器会话都没有团队密钥。团队相关面板是只读的。

界面语言：英语、俄语、中文（先看 URL 里的 `?lang=`，再看已保存的偏好，最后看浏览器）。
零知识在整个过程中都成立 — 列表只显示 id、类型、大小和时间戳，密文预览就以密文的样子呈现。

> 字体（Space Grotesk、JetBrains Mono）已内置进服务器
> （`resources/web/assets/fonts/*.woff2`），图标是内联 SVG，CSP 为 `default-src 'self'`。
> 页面完全可离线工作，不请求任何外部 CDN。中文回落到系统 CJK 字体栈 — 内置字体只含
> latin + latin-ext。

> ⚠️ 元数据包含 `accountId`（一个邮箱地址），并且会保留在审计日志中（最近 2000 条事件）。
> 对于单人自托管，运维者*就是*数据主体 — 这可以接受。管理令牌以明文放在 `X-Admin-Token`
> 请求头里：前面要放一个 TLS 终结器（见下文），否则该令牌在网络上是可见的。

## 管理 CLI

`skerry-admin` 与服务器打包在同一个镜像里（`/app/bin/skerry-admin`），驱动的是与运维控制台
相同的 `/admin` 端点 — 每个操作只有一套实现和一道授权关卡。它需要 `SKERRY_ADMIN_TOKEN`
和一个可达的服务器；它从不直接访问数据库。

```bash
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin stats
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices list --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices revoke devA --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin accounts delete alice@example.com --yes
```

| 命令 | 用途 |
|---|---|
| `health` | 存活状态和版本（无需令牌）。 |
| `stats` | 实例总量：账号、活跃设备、记录、存储。 |
| `accounts list` / `accounts records <id>` | 带汇总的账号列表；按账号列出的记录元数据。 |
| `accounts purge-tombstones <id>` | 丢弃所有设备都已同步越过的删除标记。 |
| `accounts delete <id> --yes` | 删除账号及其全部数据（不可逆，因此需要这个标志）。 |
| `devices list [--account id]` | 活跃设备，最近出现的排在前面。 |
| `devices revoke <id> --account <id>` | 吊销一台设备（它之后仍可重新认证）。 |
| `activity` | 最近的审计日志事件。 |
| `metrics` | 原始 Prometheus 指标输出（使用 `SKERRY_METRICS_TOKEN`）。 |

全局选项：`--url`（默认取 `SKERRY_ADMIN_URL`，否则 `http://127.0.0.1:$SKERRY_PORT`）、
`--token` / `--token-file`（命令行标志在 `ps` 里可见 — 优先用环境变量或密钥文件）、
`--limit`、`--json`（原样打印服务器返回的 JSON，便于交给 `jq`）、`--help`。

退出码是给脚本用的：`0` 成功，`1` 出错，`2` 用法错误，`3` 未授权，`4` 未找到，
`5` 服务器不可达。用 `--url https://sync.example.com` 可以对远程实例执行；URL 必须指向
服务器根路径（不支持反向代理的路径前缀）。

## 指标与监控

`/metrics` 提供 Prometheus 指标输出 — 默认关闭，因为在一台零知识服务器上，元数据*就是*
攻击面。用令牌来启用它：

```bash
-e SKERRY_METRICS=token -e SKERRY_METRICS_TOKEN="$(openssl rand -hex 24)"
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: skerry-sync
    static_configs: [{ targets: ["sync.example.com:8080"] }]
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/skerry-metrics-token
```

除了标准的 `jvm_*`、`process_*` 和 `hikaricp_*` 系列之外，你还能拿到：

| 指标系列 | 能回答什么 |
|---|---|
| `skerry_http_server_requests_seconds` | 按 `method`、`route`（**模板**，绝不含 id）、`status` 划分的延迟和请求量。 |
| `skerry_http_rejected_requests_total`、`skerry_http_unhandled_exceptions_total` | 在路由之前就被拒绝的请求（411/413）；服务端故障。 |
| `skerry_auth_attempts_total{kind,outcome}`、`skerry_auth_tokens_issued_total` | 登录/注册/刷新的结果 — 暴力破解的信号。 |
| `skerry_admin_auth_failures_total`、`skerry_metrics_auth_failures_total` | 静态令牌错误，也就是有人在试探控制台或抓取端点。 |
| `skerry_auth_jwt_rejected_total{reason}`、`skerry_team_authz_denied_total{reason}` | 被拒绝的设备令牌；被拒绝的团队/范围访问。 |
| `skerry_sync_records_received_total` / `_pulled_total` / `skerry_sync_push_bytes_total` | 按范围（`account`、`team`）划分的同步量。 |
| `skerry_sync_ws_sessions`、`…_opened_total`、`…_closed_total{reason}`、`…_session_duration_seconds` | 实时推送套接字：开着几条、因何关闭、持续多久。 |
| `skerry_accounts`、`skerry_devices{state}`、`skerry_records{state}`、`skerry_storage_bytes{scope}`、`skerry_db_file_bytes`、`skerry_teams`、`skerry_pairing_sessions{state}` | 存量数据，在后台刷新（见 `SKERRY_METRICS_INVENTORY_SECONDS`）。 |
| `skerry_inventory_last_success_time_seconds`、`skerry_inventory_errors_total` | 上面那些存量数据是否还新鲜。 |
| `skerry_db_up`、`skerry_db_probe_duration_seconds` | `/readyz` 背后的就绪探测。 |
| `skerry_build_info{version}`、`skerry_server_start_time_seconds` | 正在运行的版本；用于发现重启。 |

**任何标签都不会携带 accountId、deviceId、recordId、teamId、scopeId 或 IP 地址。** 这些都是
客户端自选的值：作为标签，它们会让用户把指标注册表撑到进程崩溃，而且会公开正是这套设计
刻意不让服务器接触到的元数据。按账号的数字放在管理令牌之后，见 `/admin/accounts`。

一套可以起步的告警：

```promql
skerry_db_up == 0                                                    # 数据库不可达
time() - skerry_inventory_last_success_time_seconds > 300            # 存量指标已经过期
changes(skerry_server_start_time_seconds[15m]) > 2                   # 重启循环
rate(skerry_admin_auth_failures_total[10m]) > 0                      # 有人在猜管理令牌
sum(rate(skerry_auth_attempts_total{outcome="denied"}[10m])) > 0.2   # 登录暴力破解
hikaricp_connections_pending > 0                                     # 争抢那一条 SQLite 连接
predict_linear(skerry_db_file_bytes[6h], 7*24*3600) > 20e9           # 本周之内容量就会被撑满
skerry_records{state="tombstone"} / skerry_records{state="live"} > 0.5  # 墓碑清理没有在跑
```

## 生产环境安全

- 设置一个稳定的 `SKERRY_JWT_SECRET`（否则一次重启就会让所有令牌失效），以及一个非空的
  `SKERRY_ADMIN_TOKEN`。
- 备份 = 那个 SQLite 文件（`/data`）或者 PostgreSQL 转储；数据是加密的，但这是你唯一的
  恢复点。
- 服务器本身监听明文 HTTP — TLS 由反向代理终结（见下文）。载荷本来就是端到端加密的
  （零知识），SRP 走明文也是安全的，但**管理令牌和元数据（包括 `accountId` = 邮箱地址）
  是明文传输的** — 没有 TLS 时它们在网络上可见。对于公网可达的主机，TLS 是必需的。

### TLS 终结

把客户端指向 `https://…` — `/sync` WebSocket 会自动切到 `wss://`（同一主机）。

**Caddy**（自动 Let's Encrypt，最省事的方案）：

```caddy
sync.example.com {
    reverse_proxy localhost:8080
}
```

**nginx**（自备证书或用 Certbot；`/sync` 的 WebSocket 升级必须转发过去）：

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
        # WebSocket /sync（实时拉取）：缺了这两个头，实时通知就会失效。
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h; # /sync 是长连接，不要因超时把它切断
    }
}
```

把服务器绑定到回环地址（`SKERRY_HOST=127.0.0.1`），这样 8080 端口就无法绕过代理被访问。

> **在可信局域网内不启用 TLS 自托管**是一个可以接受的、有意为之的选择：流量是端到端加密的，
> 元数据也留在局域网内。Android 客户端允许明文连接（`network_security_config.xml`）。
> 一旦这台主机能从外部访问 — 就加上 TLS。

## 测试

```bash
./gradlew :server:test
```

它们覆盖 LWW 冲突、SRP 往返、JWT、团队角色/ACL，以及完整的 HTTP 流程
（注册 → 登录 → 推送/拉取 → 设备 → 配对 → 管理）。
