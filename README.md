# RFC 3161 国密 TSA 服务器

自建 **RFC 3161** 合规时间戳授权机构（TSA），支持 **SM2 / SM3 国密算法**，生产部署方案为 **nginx + fcgiwrap + GmSSL3 + chrony**，并提供 **Java Spring Boot Starter** 客户端 SDK。

> 完整操作手册见 [`docs/operation-manual.md`](docs/operation-manual.md)

---

## 架构一览

```
客户端 (Java SDK / curl)
        │  POST /tsa  (application/timestamp-query)
        ▼
   ┌─────────┐   FastCGI :9000   ┌──────────────────────┐
   │  nginx  │ ────────────────► │ tsa-server           │
   │ 80/443  │                   │ GmSSL3 + fcgiwrap    │
   └─────────┘                   │ tsa_cgi.sh → ts reply│
                                 └──────────┬───────────┘
                                            │ 系统时钟
                                 ┌──────────▼───────────┐
                                 │ chrony (NTP 同步)    │
                                 └──────────────────────┘
```

| 组件 | 职责 |
|------|------|
| **chrony** | NTP 时间同步，保证时间戳可信 |
| **tsa-server** | GmSSL3 编译安装 + fcgiwrap + CGI 签发 SM2/SM3 时间戳 |
| **nginx** | HTTP/HTTPS 入口，FastCGI 反代到 fcgiwrap |
| **sdk** | Spring Boot Starter：`TsaClient` + `Sm2Util` + `Sm3Util` |
| **sdk-demo** | REST 演示应用（端口 9090） |

---

## 快速开始

### 1. 环境要求

- Docker 20.10+ / Docker Compose v2
- 内存建议 ≥ 4GB（首次编译 GmSSL3 较慢）
- JDK 21+、Maven 3.8+（仅 SDK / Demo 需要）

### 2. 一键启动 TSA 服务

```powershell
# Windows PowerShell
cd M:\shijianchuo
docker compose up --build -d
docker compose ps
curl http://localhost:8080/health
curl http://localhost:8080/info
```

```bash
# Linux / macOS
cd /path/to/shijianchuo
docker compose up --build -d
curl http://localhost:8080/health
```

首次构建约 **10–15 分钟**（从源码编译 GmSSL3）。

### 3. 编译 SDK 并启动 Demo

```powershell
# 根目录多模块安装
mvn clean install -DskipTests

# 启动 Demo
cd sdk-demo
mvn spring-boot:run
```

Demo 默认：`http://localhost:9090`  
TSA 默认：`http://localhost:8080/tsa`

### 4. 快速验证

```powershell
# SM3
curl "http://localhost:9090/api/sm3/hash?text=Hello"

# 时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/text `
  -H "Content-Type: application/json" `
  -d "{\"text\":\"Hello, TSA!\"}"

# 或运行自测脚本
pwsh ./scripts/test_tsa.ps1
```

---

## 项目结构

```
shijianchuo/
├── docker-compose.yml          # 一键编排: chrony + tsa-server + nginx
├── .env                        # 端口 / 证书主题 / NTP / 策略 OID
├── pom.xml                     # Maven 父工程 (sdk + sdk-demo)
├── tsa-server/                 # GmSSL3 + fcgiwrap + CGI
├── nginx/                      # 反向代理 + TLS
├── chrony/                     # NTP
├── sdk/                        # Spring Boot Starter
├── sdk-demo/                   # 示例 REST 应用
├── scripts/                    # 自测脚本
└── docs/operation-manual.md    # 完整操作手册
```

---

## 端点

| 路径 | 方法 | 说明 |
|------|------|------|
| `/tsa` | POST | RFC 3161 时间戳（`application/timestamp-query`） |
| `/tsa/cert` | GET | 下载 TSA 证书 (SM2) |
| `/tsa/cacert` | GET | 下载 CA 证书 |
| `/health` | GET | 健康检查 |
| `/info` | GET | 服务信息 JSON |

HTTPS 端口默认 `8443`（映射容器 443）。

---

## Java SDK 最小用法

`application.yml`:

```yaml
tsa:
  url: http://localhost:8080/tsa
  policy-oid: 1.2.3.4.1
  hash-algorithm: SM3
```

```java
@Autowired
private TsaClient tsaClient;

// SM3 摘要
String hex = Sm3Util.hashHex("payload");

// SM2 签名
KeyPair kp = Sm2Util.generateKeyPair();
byte[] sig = Sm2Util.sign(data, kp.getPrivate());
boolean ok = Sm2Util.verify(data, sig, kp.getPublic());

// 时间戳
TimeStampResult r = tsaClient.timestamp("Hello, TSA!");
```

依赖：

```xml
<dependency>
  <groupId>com.tsa</groupId>
  <artifactId>tsa-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 常用运维命令

```powershell
docker compose logs -f tsa-server
docker compose logs -f nginx
docker exec tsa-chrony chronyc tracking
docker exec tsa-server gmssl version
docker exec tsa-server gmssl x509 -in /etc/tsa/certs/tsacert.pem -text -noout
docker compose down        # 停服务保留卷
docker compose down -v     # 停服务并删除证书/序列号（会重生证书）
```

---

## 配置

编辑 `.env`：

| 变量 | 默认 | 说明 |
|------|------|------|
| `NGINX_HTTP_PORT` | 8080 | HTTP 映射端口 |
| `NGINX_HTTPS_PORT` | 8443 | HTTPS 映射端口 |
| `CA_ORG` / `TSA_CN` | MyOrg / TSA Server | 证书主题 |
| `TSA_POLICY_OID` | 1.2.3.4.1 | 默认策略 OID |
| `NTP_SERVER1..3` | 阿里/腾讯/pool | NTP 上游 |

---

## 文档

- [完整操作手册](docs/operation-manual.md) — 架构、证书、CGI、SDK API、生产实践、排障
- 国密算法：SM2 (`1.2.156.10197.1.301`) / SM3 (`1.2.156.10197.1.401`)

## GitHub Actions 镜像构建

仓库已内置 CI/CD 工作流（`.github/workflows/`）：

| Workflow | 文件 | 作用 |
|----------|------|------|
| **Docker Build & Push** | `docker-build.yml` | 并行构建 `tsa-server` / `nginx` / `chrony`，推送到 GHCR |
| **Maven CI** | `maven-ci.yml` | 编译 `sdk` + `sdk-demo`，上传 jar 产物 |
| **CI** | `ci.yml` | 路径变更检测与总览 Summary |

### 触发条件

- **push** 到 `main` / `master` / `develop`，或打 `v*` 标签
- **pull_request**（仅构建校验，**不推送**镜像）
- **workflow_dispatch** 手动触发（可选手动推送、按组件过滤）

### 镜像地址约定

```text
ghcr.io/<owner>/<repo>/tsa-server:<tag>
ghcr.io/<owner>/<repo>/nginx:<tag>
ghcr.io/<owner>/<repo>/chrony:<tag>
```

标签示例：`latest`（默认分支）、分支名、`sha-xxxxxxx`、`v1.0.0` → `1.0.0`。

### 使用预构建镜像部署

```powershell
# 登录 GHCR（Package 为 private 时需要）
echo $env:GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USER --password-stdin

$env:GHCR_OWNER = "your-org"      # 小写
$env:GHCR_REPO  = "shijianchuo"   # 小写
$env:IMAGE_TAG  = "latest"

docker compose -f docker-compose.ghcr.yml pull
docker compose -f docker-compose.ghcr.yml up -d
```

### 首次使用注意

1. 推送代码后在 GitHub **Actions** 页查看运行状态（`tsa-server` 因编译 GmSSL3，首次约 10–20 分钟）。
2. 仓库 **Settings → Actions → General** 确保允许 workflow 读写。
3. 推送成功后到 **Packages** 查看镜像；若需公开拉取，将 Package 可见性设为 **Public**。
4. 无需额外 Secret：使用内置 `GITHUB_TOKEN` 推送 `ghcr.io`。

---

## 许可与免责

本工程面向研发与存证场景演示。生产环境请使用正式 CA 签发证书、HSM 保护私钥，并完成等保/密评相关合规评估。
