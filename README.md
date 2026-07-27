# RFC 3161 国密 TSA 服务器

自建 **RFC 3161** 合规时间戳授权机构（TSA），支持 **SM2 / SM3 国密算法**。

**最终唯一镜像** `ghcr.io/xiaochen201807/shijianchuo/tsa`：

```text
Tongsuo(RFC3161 TSA) + nginx + fcgiwrap + chrony
        + tsa-demo 原生二进制 (GraalVM，无 JVM)
```

多阶段构建：先编出 `tsa-demo`，再打进运行时镜像，由 **supervisor 一起拉起**。  
对外：`/tsa` 时间戳，`/api/*` 为 Demo REST（反代到原生进程）。

> - 完整操作手册：[`docs/operation-manual.md`](docs/operation-manual.md)  
> - **服务器拉取部署 + 测试**（Compose 全文）：[`docs/server-deploy-and-test.md`](docs/server-deploy-and-test.md)

---

## 架构一览

```
客户端 (Java SDK / curl)
        │  POST /tsa
        ▼
┌─────────────────────────────────────────────┐
│  单个容器 tsa (All-in-One)                   │
│                                             │
│  nginx :80/:443                             │
│      │ FastCGI 127.0.0.1:9000               │
│      ▼                                      │
│  fcgiwrap → tsa_cgi.sh → gmssl ts -reply    │
│  chronyd (NTP)                              │
│  supervisor 进程托管                         │
└─────────────────────────────────────────────┘
```

| 进程 | 职责 |
|------|------|
| **chronyd** | NTP 时间同步 |
| **fcgiwrap** | 执行 CGI，调用 GmSSL3 签发 SM2/SM3 时间戳 |
| **nginx** | HTTP/HTTPS 入口与证书下载 |
| **sdk** | Spring Boot Starter：`TsaClient` + `Sm2Util` + `Sm3Util` |

---

## 快速开始

### 1. 环境要求

- Docker 20.10+ / Docker Compose v2
- 内存建议 ≥ 4GB（首次编译 GmSSL3 较慢）
- JDK 21+、Maven 3.8+（仅 SDK / Demo 需要）

### 2. 本地一键启动  

```powershell
cd M:\shijianchuo
docker compose up --build -d
docker compose ps
curl http://localhost:8080/health
curl http://localhost:8080/info
```

首次构建约 **10–15 分钟**（编译 GmSSL3）。

### 3. 拉取最终镜像（服务 + 原生 Demo，无 JVM）

```bash
docker pull ghcr.io/xiaochen201807/shijianchuo/tsa:latest
docker compose -f docker-compose.ghcr.yml up -d

curl http://localhost:8080/health
curl "http://localhost:8080/api/sm3/hash?text=Hello"
curl -X POST http://localhost:8080/api/tsa/timestamp/text \
  -H "Content-Type: application/json" -d '{"text":"Hello, TSA!"}'
```

| 端口 | 用途 |
|------|------|
| `8080` | TSA `/tsa` + Demo `/api/*`（推荐） |
| `8443` | HTTPS |
| `9090` | Demo 直连（可选） |

完整手册：[`docs/server-deploy-and-test.md`](docs/server-deploy-and-test.md)

### 4. 开发时单独编 Demo 二进制（可选）

```bash
bash ./scripts/build-native.sh          # → sdk-demo/target/tsa-demo
# 生产请用最终镜像，不必单独跑 tsa-demo 镜像
```

---

## 项目结构

```
shijianchuo/
├── Dockerfile                  # All-in-One 镜像（唯一构建入口）
├── docker/all-in-one/          # supervisor / nginx / chrony / 入口脚本
├── docker-compose.yml          # 本地构建并启动单容器
├── docker-compose.ghcr.yml     # 拉取 GHCR 镜像启动
├── tsa-server/                 # CGI、证书脚本、GmSSL 配置（被 Dockerfile COPY）
├── sdk/                        # Spring Boot Starter
├── sdk-demo/                   # REST 演示
└── .github/workflows/          # 多架构构建推送 ghcr.io/.../tsa
```

> 历史分体目录 `nginx/`、`chrony/` 及旧 Dockerfile 已不再用于部署，仅作参考。

---

## 端点

| 路径 | 方法 | 说明 |
|------|------|------|
| `/tsa` | POST | RFC 3161 时间戳 |
| `/tsa/cert` | GET | 下载 TSA 证书 (SM2) |
| `/tsa/cacert` | GET | 下载 CA 证书 |
| `/health` | GET | 健康检查 |
| `/info` | GET | 服务信息 JSON |

默认端口映射：`8080→80`，`8443→443`。

---

## Java SDK 最小用法

```yaml
tsa:
  url: http://localhost:8080/tsa
  policy-oid: 1.2.3.4.1
  hash-algorithm: SM3
```

```java
@Autowired TsaClient tsaClient;
TimeStampResult r = tsaClient.timestamp("Hello, TSA!");
```

```xml
<dependency>
  <groupId>com.tsa</groupId>
  <artifactId>tsa-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## GitHub Actions

| Workflow | 说明 |
|----------|------|
| **Docker Build & Push** | **唯一发布流水线**：GraalVM native + Tongsuo 运行时 → `.../tsa`（无 JVM） |
| **Maven CI** | 仅校验 SDK **库 jar** 编译（给其它 Java 项目依赖，不发布运行镜像） |

```bash
docker pull ghcr.io/xiaochen201807/shijianchuo/tsa:latest
docker buildx imagetools inspect ghcr.io/xiaochen201807/shijianchuo/tsa:latest
```

---

## 运维命令

```powershell
docker compose logs -f tsa
docker exec tsa gmssl version
docker exec tsa supervisorctl status
docker exec tsa chronyc tracking
docker compose down
docker compose down -v   # 删除证书与数据卷
```

---

## 许可与免责

本工程面向研发与存证场景演示。生产环境请使用正式 CA 签发证书、HSM 保护私钥，并完成等保/密评相关合规评估。
