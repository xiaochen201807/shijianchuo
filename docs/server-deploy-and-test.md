# All-in-One 镜像：服务器拉取部署与测试手册

> **适用镜像**：`ghcr.io/xiaochen201807/shijianchuo/tsa`（**唯一最终镜像**）  
> **架构**：`linux/amd64` + `linux/arm64`  
> **内含**：Tongsuo(TSA) + nginx + fcgiwrap + chrony + **`tsa-demo` 原生二进制（无 JVM）**  
> **仓库**：https://github.com/xiaochen201807/shijianchuo  
>
> 多阶段构建：GraalVM 编出 `tsa-demo` → 拷入运行时镜像，由 supervisor 与 TSA 一起运行。

---

## 目录

1. [镜像说明](#1-镜像说明)
2. [服务器环境要求](#2-服务器环境要求)
3. [登录 GHCR（私有包时）](#3-登录-ghcr私有包时)
4. [Compose 部署文件（完整内容）](#4-compose-部署文件完整内容)
5. [一键启动步骤](#5-一键启动步骤)
6. [端口与端点](#6-端口与端点)
7. [测试使用方法](#7-测试使用方法)
8. [运维常用命令](#8-运维常用命令)
9. [故障排查](#9-故障排查)

---

## 1. 镜像说明

| 项 | 值 |
|----|-----|
| 镜像地址 | `ghcr.io/xiaochen201807/shijianchuo/tsa` |
| 推荐标签 | `latest`（也可使用 `main`、`sha-xxxxxxx`、语义化版本） |
| 容器名 | `tsa` |
| 对外端口 | HTTP `8080→80`，HTTPS `8443→443`（可改） |
| 时间戳路径 | `POST /tsa` |
| 算法 | SM2 签名 + SM3 摘要（RFC 3161） |

```bash
# 查看镜像是否支持双架构
docker buildx imagetools inspect ghcr.io/xiaochen201807/shijianchuo/tsa:latest
```

---

## 2. 服务器环境要求

- 操作系统：Linux（推荐 Ubuntu 20.04+ / CentOS 7+ / Debian 11+）
- Docker：20.10+
- Docker Compose：v2（`docker compose` 命令）
- 内存：建议 ≥ 1GB（运行态；构建镜像另说）
- 磁盘：≥ 2GB
- 网络：能访问 `ghcr.io`（拉取镜像）；能访问 NTP 上游更佳（时间同步）

安装 Docker（Ubuntu 示例）：

```bash
curl -fsSL https://get.docker.com | sudo bash
sudo usermod -aG docker "$USER"
# 重新登录后生效
docker --version
docker compose version
```

---

## 3. 登录 GHCR（私有包时）

若 Package 为 **Public**，可跳过登录。  
若为 **Private**，需使用有 `read:packages` 权限的 Token：

```bash
# 将 YOUR_GITHUB_USER 换成你的 GitHub 用户名
# 将 ghp_xxx 换成 Personal Access Token（或 GITHUB_TOKEN）
echo "ghp_xxx" | docker login ghcr.io -u YOUR_GITHUB_USER --password-stdin
```

公开包可见性设置路径：  
GitHub → 仓库 → **Packages** → 镜像 `tsa` → **Package settings** → Change visibility → Public。

---

## 4. Compose 部署文件（完整内容）

在服务器上新建目录并写入下列文件即可（**可直接复制使用**）。

### 4.1 目录结构

```text
/opt/tsa/                 # 任意路径均可
├── docker-compose.yml    # 见下文
└── .env                  # 见下文（可选，有默认值）
```

```bash
sudo mkdir -p /opt/tsa
cd /opt/tsa
```

### 4.2 `docker-compose.yml`（服务器专用 · 拉取镜像）

将以下内容保存为 `/opt/tsa/docker-compose.yml`：

```yaml
# ============================================================
# RFC 3161 国密 TSA — All-in-One（服务器拉取部署）
# 镜像: ghcr.io/xiaochen201807/shijianchuo/tsa
# 架构: linux/amd64 + linux/arm64
# ============================================================

services:
  tsa:
    image: ghcr.io/xiaochen201807/shijianchuo/tsa:${IMAGE_TAG:-latest}
    pull_policy: always
    container_name: tsa
    restart: always
    # chrony 校正系统时钟需要（All-in-One 内含 chronyd）
    privileged: true
    cap_add:
      - SYS_TIME
    ports:
      # 宿主机端口:容器端口 — 可按需修改左侧
      - "${NGINX_HTTP_PORT:-8080}:80"
      - "${NGINX_HTTPS_PORT:-8443}:443"
      - "${DEMO_PORT:-9090}:9090"
    volumes:
      - tsa-certs:/etc/tsa/certs          # SM2 CA/TSA 证书与私钥
      - tsa-data:/var/lib/tsa             # 序列号等数据
      - tsa-logs:/var/log/tsa             # TSA CGI 日志
      - nginx-logs:/var/log/nginx         # Nginx 访问/错误日志
      - nginx-tls:/etc/nginx/tls          # HTTPS 传输层证书
      - chrony-data:/var/lib/chrony       # NTP 漂移数据
    environment:
      - TZ=Asia/Shanghai
      - TSA_POLICY_OID=${TSA_POLICY_OID:-1.2.3.4.1}
      - CA_COUNTRY=${CA_COUNTRY:-CN}
      - CA_STATE=${CA_STATE:-Beijing}
      - CA_LOCALITY=${CA_LOCALITY:-Beijing}
      - CA_ORG=${CA_ORG:-MyOrg}
      - CA_OU=${CA_OU:-TSA}
      - CA_CN=${CA_CN:-TSA Root CA}
      - TSA_CN=${TSA_CN:-TSA Server}
      - CERT_DAYS=${CERT_DAYS:-3650}
    healthcheck:
      test: ["CMD", "/scripts/healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 90s

volumes:
  tsa-certs:
    driver: local
  tsa-data:
    driver: local
  tsa-logs:
    driver: local
  nginx-logs:
    driver: local
  nginx-tls:
    driver: local
  chrony-data:
    driver: local
```

> 说明：首次启动若卷中无证书，容器会自动生成 **自签 SM2 CA + TSA 证书**。生产环境请替换为正式证书。

### 4.3 `.env`（可选配置）

将以下内容保存为 `/opt/tsa/.env`：

```env
# 镜像标签
IMAGE_TAG=latest

# 对外端口（宿主机）
NGINX_HTTP_PORT=8080
NGINX_HTTPS_PORT=8443

# 证书主题（仅首次生成自签证书时生效；已有卷中证书时不会重生成）
CA_COUNTRY=CN
CA_STATE=Beijing
CA_LOCALITY=Beijing
CA_ORG=MyOrg
CA_OU=TSA
CA_CN=TSA Root CA
TSA_CN=TSA Server
CERT_DAYS=3650

# TSA 策略 OID
TSA_POLICY_OID=1.2.3.4.1
```

修改端口示例：若 8080 被占用，将 `NGINX_HTTP_PORT=9080`。

### 4.4 与仓库内文件的对应关系

| 服务器文件 | 仓库中对应文件 |
|------------|----------------|
| 上文 `docker-compose.yml` | [`docker-compose.ghcr.yml`](../docker-compose.ghcr.yml)（逻辑一致，镜像写死为本仓库地址更便于服务器复制） |
| 上文 `.env` | [`.env.example`](../.env.example) |

---

## 5. 一键启动步骤

在服务器 `/opt/tsa` 目录执行：

```bash
cd /opt/tsa

# 1)（私有包）登录
# echo "ghp_xxx" | docker login ghcr.io -u YOUR_USER --password-stdin

# 2) 拉取最新镜像
docker compose pull

# 3) 后台启动
docker compose up -d

# 4) 查看状态（等待 healthy，约 30–90 秒）
docker compose ps
docker logs -f tsa
# Ctrl+C 退出日志跟随
```

期望状态：

```text
NAME   IMAGE                                              STATUS
tsa    ghcr.io/xiaochen201807/shijianchuo/tsa:latest      Up (healthy)
```

停止 / 卸载：

```bash
# 停止并删除容器（保留证书与数据卷）
docker compose down

# 停止并删除容器 + 全部数据卷（会重新生成证书，慎用）
docker compose down -v
```

升级镜像：

```bash
cd /opt/tsa
docker compose pull
docker compose up -d
```

---

## 6. 端口与端点

假设使用默认端口映射：

| 用途 | URL |
|------|-----|
| 健康检查 | `http://<服务器IP>:8080/health` |
| 服务信息 | `http://<服务器IP>:8080/info` |
| 时间戳请求 | `http://<服务器IP>:8080/tsa` |
| **Demo API（原生二进制）** | `http://<服务器IP>:8080/api/...` 或 `:9090/api/...` |
| SM3 示例 | `http://<服务器IP>:8080/api/sm3/hash?text=Hello` |
| 时间戳示例 | `POST http://<服务器IP>:8080/api/tsa/timestamp/text` |
| 下载 TSA 证书 | `http://<服务器IP>:8080/tsa/cert` |
| 下载 CA 证书 | `http://<服务器IP>:8080/tsa/cacert` |
| HTTPS | `https://<服务器IP>:8443/...`（默认自签 TLS，浏览器会告警） |

防火墙放行示例（firewalld）：

```bash
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --permanent --add-port=8443/tcp
sudo firewall-cmd --reload
```

---

## 7. 测试使用方法

下列命令在**能访问该服务器 8080 端口**的机器上执行（本机或跳板机均可）。  
将 `SERVER` 换成实际地址，例如 `http://192.168.1.100:8080` 或 `http://localhost:8080`。

```bash
export SERVER="http://localhost:8080"
```

### 7.1 健康检查

```bash
curl -sS "${SERVER}/health"
# 期望输出: OK
```

### 7.2 服务信息

```bash
curl -sS "${SERVER}/info"
# 期望类似:
# {"service":"TSA","version":"2.0","mode":"all-in-one","algorithms":["SM2","SM3"],"rfc":"3161"}
```

### 7.3 下载证书

```bash
curl -sS "${SERVER}/tsa/cert"   -o tsacert.pem
curl -sS "${SERVER}/tsa/cacert" -o cacert.pem
ls -l tsacert.pem cacert.pem
# 可用 openssl 粗看（国密证书部分工具显示有限）
openssl x509 -in tsacert.pem -inform PEM -noout -subject -dates 2>/dev/null || true
```

### 7.4 容器内进程与时间同步

```bash
# 进程状态
docker exec tsa supervisorctl status
# 期望 chronyd / fcgiwrap / nginx 均为 RUNNING

# 国密 OpenSSL (Tongsuo)
docker exec tsa openssl version
# 或
docker exec tsa /usr/local/tongsuo/bin/openssl version

# NTP 跟踪（需要容器有网络访问 NTP）
docker exec tsa chronyc tracking

# 证书是否存在
docker exec tsa ls -la /etc/tsa/certs/
```

### 7.5 RFC 3161 时间戳请求（容器内 GmSSL 端到端）

若服务器/本机没有 GmSSL，可直接在容器内生成请求并经 nginx 回环验证：

```bash
# 在容器内创建测试数据
docker exec tsa bash -c 'echo "hello-tsa-$(date +%s)" > /tmp/test.txt'

# 生成 TimeStampReq（SM3）并签发（容器内 Tongsuo）
docker exec tsa bash -c '
  export PATH=/usr/local/tongsuo/bin:$PATH
  export LD_LIBRARY_PATH=/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64:$LD_LIBRARY_PATH
  openssl ts -query -data /tmp/test.txt -sm3 -cert -out /tmp/query.tsq
  ls -l /tmp/query.tsq
  curl -sS -X POST http://127.0.0.1/tsa \
    -H "Content-Type: application/timestamp-query" \
    --data-binary @/tmp/query.tsq \
    -o /tmp/response.tsr
  ls -l /tmp/response.tsr
  openssl ts -reply -in /tmp/response.tsr -text 2>/dev/null | head -40 || \
    echo "response size: $(stat -c%s /tmp/response.tsr) bytes"
'
```

从**宿主机**发送（请求文件需先拷出或在宿主机生成）：

```bash
# 从容器拷出请求文件
docker cp tsa:/tmp/query.tsq ./query.tsq

curl -sS -X POST "${SERVER}/tsa" \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @query.tsq \
  -o response.tsr

ls -l response.tsr
# 非空且体积 > 0 即基本成功
```

### 7.6 使用 Java SDK / Demo 测试（开发机）

开发机需能访问 `${SERVER}/tsa`。

```bash
# 1. 克隆并安装 SDK
git clone https://github.com/xiaochen201807/shijianchuo.git
cd shijianchuo
mvn clean install -DskipTests

# 2. 配置 Demo 指向服务器
# 编辑 sdk-demo/src/main/resources/application.yml
# tsa.url: http://<服务器IP>:8080/tsa

# 3. 启动 Demo
cd sdk-demo
mvn spring-boot:run
```

```bash
# SM3
curl -sS "http://localhost:9090/api/sm3/hash?text=Hello"

# 对文本打时间戳（经 Demo → 远程 TSA）
curl -sS -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
```

期望 JSON 中含：

- `"success": true`
- `"serialNumber"` / `"genTime"` / `"tokenBase64"` 等字段

### 7.7 一键冒烟脚本（仅测 TSA 服务，不依赖 Demo）

在服务器或任意可访问机器上：

```bash
#!/usr/bin/env bash
# smoke-tsa.sh
set -euo pipefail
SERVER="${1:-http://localhost:8080}"

echo "[1] health"
curl -sf "${SERVER}/health" | grep -q OK && echo "  OK" || { echo "  FAIL"; exit 1; }

echo "[2] info"
curl -sf "${SERVER}/info" | tee /tmp/tsa-info.json
echo ""

echo "[3] certs"
curl -sf "${SERVER}/tsa/cert"   -o /tmp/tsacert.pem
curl -sf "${SERVER}/tsa/cacert" -o /tmp/tsacacert.pem
test -s /tmp/tsacert.pem && test -s /tmp/tsacacert.pem && echo "  certs OK"

echo "[4] native demo API (no JVM)"
curl -sf "${SERVER}/api/sm3/hash?text=Hello" | tee /tmp/sm3.json
echo ""

echo "[5] container processes (optional)"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx tsa; then
  docker exec tsa supervisorctl status || true
  docker exec tsa ls -lh /usr/local/bin/tsa-demo
fi

echo "Smoke test passed for ${SERVER}"
```

```bash
chmod +x smoke-tsa.sh
./smoke-tsa.sh http://127.0.0.1:8080
```

---

## 8. 运维常用命令

```bash
# 日志
docker logs -f tsa
docker exec tsa tail -f /var/log/nginx/access.log
docker exec tsa tail -f /var/log/tsa/tsa_cgi.log

# 进程
docker exec tsa supervisorctl status
docker exec tsa supervisorctl restart nginx
docker exec tsa supervisorctl restart fcgiwrap

# 导出证书备份
docker cp tsa:/etc/tsa/certs/tsacert.pem ./tsacert.pem
docker cp tsa:/etc/tsa/certs/cacert.pem  ./cacert.pem
# 私钥务必妥善保管，不要提交到 git
# docker cp tsa:/etc/tsa/certs/tsakey.pem ./tsakey.pem

# 资源占用
docker stats tsa --no-stream
```

---

## 9. 故障排查

### 9.1 `docker pull` 失败 / denied

```bash
# 登录 GHCR
echo "TOKEN" | docker login ghcr.io -u USER --password-stdin

# 确认镜像名全小写
docker pull ghcr.io/xiaochen201807/shijianchuo/tsa:latest
```

### 9.2 容器一直 unhealthy

```bash
docker logs tsa
docker exec tsa /scripts/healthcheck.sh
docker exec tsa supervisorctl status
docker exec tsa curl -sS http://127.0.0.1/health
```

常见原因：证书生成失败、80 端口进程未起、磁盘满。

### 9.3 `POST /tsa` 返回 500

```bash
docker exec tsa cat /var/log/tsa/tsa_error.log
docker exec tsa ls -la /etc/tsa/certs/
```

### 9.4 时间不准

```bash
docker exec tsa date
docker exec tsa chronyc tracking
docker exec tsa chronyc sources
# 确认服务器能访问 ntp.aliyun.com 等
```

### 9.5 需要重新生成自签证书

```bash
cd /opt/tsa
docker compose down -v    # 删除卷
docker compose up -d      # 重新生成
```

---

## 附录 A：Java SDK 配置片段

```yaml
# application.yml
tsa:
  url: http://<服务器IP>:8080/tsa
  connect-timeout: 5000
  read-timeout: 30000
  policy-oid: 1.2.3.4.1
  cert-req: true
  hash-algorithm: SM3
```

```xml
<dependency>
  <groupId>com.tsa</groupId>
  <artifactId>tsa-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 附录 B：Content-Type 约定（RFC 3161）

| 方向 | Content-Type |
|------|----------------|
| 请求 | `application/timestamp-query` |
| 响应 | `application/timestamp-reply` |

---

**文档版本**：2.0.0  
**镜像**：`ghcr.io/xiaochen201807/shijianchuo/tsa:latest`  
**相关文档**：[完整操作手册](./operation-manual.md) · [仓库 README](../README.md)
