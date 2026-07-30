# RFC 3161 国密 TSA 服务器 — 完整操作手册

> **版本**: 3.0.0  
> **日期**: 2026-07-27  
> **适用场景**: 电子签名、存证、时间戳服务  
> **加密算法**: SM2 (签名) + SM3 (摘要) 国密算法  
> **部署方案**: **All-in-One 单镜像** (Tongsuo + nginx + fcgiwrap + chrony，supervisor 托管)

---

## 目录

1. [项目概述](#1-项目概述)
2. [系统架构](#2-系统架构)
3. [环境准备](#3-环境准备)
4. [快速启动 (一键部署)](#4-快速启动-一键部署)
5. [项目结构说明](#5-项目结构说明)
6. [Docker Compose 配置详解](#6-docker-compose-配置详解)
7. [国密证书管理](#7-国密证书管理)
8. [TSA Server 详解](#8-tsa-server-详解)
9. [Nginx 反向代理配置](#9-nginx-反向代理配置)
10. [Chrony 时间同步](#10-chrony-时间同步)
11. [Java SDK 使用指南](#11-java-sdk-使用指南)
12. [Demo 应用 API 文档](#12-demo-应用-api-文档)
13. [测试与验证](#13-测试与验证)
14. [生产部署最佳实践](#14-生产部署最佳实践)
15. [故障排除](#15-故障排除)
16. [附录: OID 与算法参考](#16-附录-oid-与算法参考)
17. [GitHub Actions 镜像构建](#17-github-actions-镜像构建)

---

## 1. 项目概述

### 1.1 什么是 TSA

TSA (Time Stamping Authority) 是时间戳授权机构，遵循 **RFC 3161** 协议标准。它为电子数据提供不可篡改的时间证明，广泛应用于：

- **电子签名**: 证明签名行为发生的确切时间
- **存证**: 法律证据的时间锚定
- **区块链**: 区块时间戳验证
- **文档管理**: 文档创建/修改时间证明

### 1.2 国密算法支持

本项目使用 **Tongsuo** 库提供以下国密算法:

| 算法 | 类型 | 用途 | OID |
|------|------|------|-----|
| SM2 | 非对称加密/签名 | TSA 证书签名、时间戳令牌签名 | 1.2.156.10197.1.301 |
| SM3 | 密码杂凑 | 消息摘要、时间戳请求摘要 | 1.2.156.10197.1.401 |

### 1.3 核心特性

- ✅ RFC 3161 时间戳协议完整实现
- ✅ SM2 椭圆曲线数字签名
- ✅ SM3 密码杂凑算法
- ✅ Tongsuo 国密密码库
- ✅ nginx + fcgiwrap 生产级部署
- ✅ chrony NTP 时间同步
- ✅ Docker Compose 一键部署
- ✅ Java Spring Boot Starter SDK
- ✅ 自动生成 SM2 CA 和 TSA 证书

---

## 2. 系统架构

### 2.1 架构图 (All-in-One)

```
┌──────────────────────────────────────────────────────────────────┐
│  容器 tsa (单镜像 / supervisor 托管全部进程)                       │
│                                                                  │
│   nginx :80/:443  ──FastCGI──►  fcgiwrap :9000 (127.0.0.1)      │
│                                      │                           │
│                                      ▼                           │
│                               tsa_cgi.sh → openssl ts (Tongsuo)  │
│                                                                  │
│   chronyd (NTP)                                                  │
│   tsa-demo 原生二进制 :9090 (GraalVM, 无 JVM)                    │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                      客户端 / Java SDK
```

### 2.2 请求流程

```
客户端 (Java SDK)
    │
    │ 1. POST /tsa
    │    Content-Type: application/timestamp-query
    │    Body: DER编码的TimeStampReq
    │
    ▼
┌─────────┐     2. fastcgi_pass      ┌──────────────┐     3. 执行CGI     ┌─────────────┐
│  nginx  │ ──────────────────────► │  fcgiwrap    │ ──────────────────► │ tsa_cgi.sh  │
│  :8080  │                         │  :9000       │                     │             │
└─────────┘                         └──────────────┘                     └──────┬──────┘
                                                                                │
                                         4. openssl ts -reply (Tongsuo)         │
                                         -signer tsacert.pem                    ▼
                                         -inkey tsakey.pem               ┌───────────┐
                                         -config tsa.cnf                  │ Tongsuo   │
                                         │                                │ (SM2/SM3) │
                                         ▼                                └───────────┘
                                  ┌──────────────┐
                                  │  DER 编码的   │
                                  │TimeStampResp │
                                  └──────────────┘
```

### 2.3 组件职责

| 组件 | 职责 | 技术 |
|------|------|------|
| **chronyd** | NTP时间同步，确保TSA时间戳精确 | chrony (supervisor 托管) |
| **fcgiwrap** | CGI 到 FastCGI 适配器 | spawn-fcgi + fcgiwrap |
| **tsa_cgi.sh** | 时间戳生成核心脚本 | Tongsuo openssl ts |
| **nginx** | HTTP/HTTPS 反向代理 | nginx |
| **tsa-demo** | Demo REST API (原生二进制) | GraalVM Native Image (无 JVM) |
| **SDK** | Java客户端开发包 | Spring Boot + BouncyCastle |

---

## 3. 环境准备

### 3.1 系统要求

- **操作系统**: Windows 10/11, macOS, Linux
- **Docker**: 20.10+ (含 Docker Compose v2)
- **内存**: 2GB+ (推荐 4GB)
- **磁盘**: 2GB+ (Tongsuo编译需要空间)
- **网络**: 需要访问外网 (NTP同步 + Docker镜像拉取)
- **Java**: JDK 21+ (仅SDK开发需要)
- **Maven**: 3.8+ (仅SDK开发需要)

### 3.2 安装 Docker Desktop

#### Windows
```powershell
# 下载 Docker Desktop for Windows
# 官网: https://www.docker.com/products/docker-desktop

# 安装后验证
docker --version
docker compose version
```

#### macOS
```bash
# 使用 Homebrew 安装
brew install --cask docker

# 或从官网下载
# https://www.docker.com/products/docker-desktop

# 验证
docker --version
docker compose version
```

#### Linux (Ubuntu/Debian)
```bash
# 安装 Docker
curl -fsSL https://get.docker.com | sudo bash

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 安装 Docker Compose v2
sudo apt-get install docker-compose-plugin

# 验证
docker --version
docker compose version
```

### 3.3 安装 Java 和 Maven (SDK开发)

#### Windows (使用 winget)
```powershell
# 安装 OpenJDK 21 (或使用 Dragonwell 21)
winget install Microsoft.OpenJDK.21

# 安装 Maven
winget install Apache.Maven

# 验证
java -version
mvn -version
```

#### macOS
```bash
brew install openjdk@21 maven
```

#### Linux
```bash
sudo apt install openjdk-21-jdk maven
```

---

## 4. 快速启动 (一键部署)

### 4.1 克隆/进入项目

```powershell
# Windows PowerShell
cd M:\shijianchuo
```

```bash
# Linux/macOS
cd /path/to/shijianchuo
```

### 4.2 修改配置 (可选)

复制 `.env.example` 为 `.env`，按需修改:

```bash
cp .env.example .env
```

完整配置项说明:

```env
# --- 端口配置 ---
NGINX_HTTP_PORT=8080       # HTTP 端口 (映射到容器 80)
NGINX_HTTPS_PORT=8443      # HTTPS 端口 (映射到容器 443)

# --- 证书配置 ---
CA_COUNTRY=CN              # CA 证书国家代码
CA_STATE=Beijing           # CA 证书省份
CA_LOCALITY=Beijing        # CA 证书城市
CA_ORG=MyOrg               # CA 证书组织名
CA_OU=TSA                  # CA 证书部门
CA_CN=TSA Root CA          # CA 证书通用名
TSA_CN=TSA Server          # TSA 证书通用名
CERT_DAYS=3650             # 证书有效期 (天)

# --- TSA 策略 OID ---
TSA_POLICY_OID=1.2.3.4.1
TSA_OTHER_POLICIES=1.2.3.4.5

# --- NTP 服务器 ---
NTP_SERVER1=ntp.aliyun.com
NTP_SERVER2=ntp.tencent.com
NTP_SERVER3=cn.pool.ntp.org

# --- 日志级别 ---
LOG_LEVEL=info
```

> **提示**: 大多数配置项使用默认值即可，仅端口冲突或需要自定义证书主题时才需修改。

### 4.3 一键启动

```powershell
# Windows PowerShell
docker compose up --build -d
```

```bash
# Linux/macOS
docker compose up --build -d
```

**首次构建大约需要 15-20 分钟** (Tongsuo 源码编译 + GraalVM 原生镜像编译较慢)。

### 4.4 查看启动状态

```bash
# 查看容器状态
docker compose ps

# 预期输出:
# NAME   STATUS      PORTS
# tsa    Up (healthy)  0.0.0.0:8080->80/tcp, 0.0.0.0:8443->443/tcp, 0.0.0.0:9090->9090/tcp
```

容器内由 supervisor 托管 4 个进程: chronyd、fcgiwrap、nginx、tsa-demo。

### 4.5 查看日志

```bash
# 查看容器日志 (含所有组件)
docker compose logs -f

# 查看 CGI 日志
docker exec tsa cat /var/log/tsa/tsa_cgi.log

# 查看 nginx 日志
docker exec tsa cat /var/log/nginx/error.log

# 查看 supervisor 进程状态
docker exec tsa supervisorctl status
```

### 4.6 快速测试

```bash
# 健康检查
curl http://localhost:8080/health
# 预期输出: OK

# 服务器信息
curl http://localhost:8080/info
# 预期输出: {"service":"TSA","version":"3.0","mode":"all-in-one","components":["tongsuo","nginx","fcgiwrap","chrony","tsa-demo-native"],"algorithms":["SM2","SM3"],"rfc":"3161","demo":"/api","jvm":false}
```

### 4.7 停止服务

```bash
# 停止并移除容器 (保留数据)
docker compose down

# 停止并移除容器和所有数据 (重新生成证书)
docker compose down -v
```

---

## 5. 项目结构说明

```
shijianchuo/
├── Dockerfile                        # All-in-One 多阶段构建 (GraalVM + Tongsuo)
├── docker-compose.yml                # Docker Compose 编排文件 (单容器)
├── .env.example                      # 环境变量示例
│
├── docker/all-in-one/                # All-in-One 容器配置
│   ├── nginx.conf                    # Nginx 配置 (FastCGI + 反代)
│   ├── entrypoint.sh                 # 容器入口脚本
│   ├── supervisord.conf              # supervisor 进程托管配置
│   ├── chrony.conf                   # Chrony NTP 配置
│   ├── generate_tls_certs.sh         # TLS 证书生成脚本
│   ├── openssl.cnf                   # 兜底 openssl.cnf
│   ├── openssl-env.sh                # OpenSSL 环境变量
│   └── healthcheck.sh                # 健康检查脚本
│
├── tsa-server/                       # TSA 配置与脚本
│   ├── config/
│   │   ├── tsa.cnf                   # Tongsuo TSA 配置
│   │   └── tsa_ext.cnf              # 证书扩展配置
│   └── scripts/
│       ├── generate_certs.sh         # SM2 证书生成脚本
│       └── tsa_cgi.sh                # CGI 时间戳处理脚本
│
├── sdk/                              # Java Spring Boot Starter SDK
│   ├── pom.xml
│   └── src/main/java/com/tsa/starter/
│       ├── TsaAutoConfiguration.java
│       ├── TsaClient.java            # TSA 客户端核心
│       ├── TsaProperties.java
│       ├── sm2/Sm2Util.java
│       ├── sm3/Sm3Util.java
│       ├── model/TimeStampResult.java
│       └── exception/TsaException.java
│
├── sdk-demo/                         # Demo 示例应用 (编译为 GraalVM 原生二进制)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/tsa/demo/
│       │   ├── DemoApplication.java
│       │   └── controller/TsaDemoController.java
│       └── resources/application.yml
│
├── docs/
│   └── operation-manual.md           # 本文档
│
├── docker-compose.ghcr.yml           # 使用 GHCR 预构建镜像部署
└── .github/workflows/
    ├── docker-build.yml              # Docker 镜像构建推送 GHCR
    ├── maven-ci.yml                  # Java SDK 编译 CI
    └── ci.yml                        # 路径变更总览
```

---

## 6. Docker Compose 配置详解

### 6.1 服务定义

`docker-compose.yml` 定义了 1 个 All-in-One 服务:

| 服务 | 镜像 | 端口 | 内含组件 |
|------|------|------|------|
| tsa | 自建 (Ubuntu + Tongsuo + nginx + fcgiwrap + chrony + tsa-demo) | 80, 443, 9090 | supervisor 托管全部进程 |

### 6.2 数据卷

| 卷名 | 用途 | 挂载点 |
|------|------|--------|
| tsa-certs | TSA SM2 证书 | /etc/tsa/certs |
| tsa-data | TSA 数据 | /var/lib/tsa |
| tsa-logs | TSA CGI 日志 | /var/log/tsa |
| nginx-logs | Nginx 日志 | /var/log/nginx |
| nginx-tls | Nginx TLS 证书 | /etc/nginx/tls |
| chrony-data | Chrony 数据 | /var/lib/chrony |

### 6.3 容器内通信

单容器架构，所有组件通过 localhost 通信:
- nginx → fcgiwrap: `127.0.0.1:9000` (FastCGI)
- nginx → tsa-demo: `127.0.0.1:9090` (HTTP 反代)
- tsa-demo → nginx: `127.0.0.1:80/tsa` (TSA 请求闭环)

### 6.4 环境变量

完整环境变量列表参见 `.env.example`，主要分类:

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NGINX_HTTP_PORT` | 8080 | HTTP 端口 |
| `NGINX_HTTPS_PORT` | 8443 | HTTPS 端口 |
| `CA_COUNTRY` | CN | CA 证书国家代码 |
| `CA_STATE` | Beijing | CA 证书省份 |
| `CA_LOCALITY` | Beijing | CA 证书城市 |
| `CA_ORG` | MyOrg | CA 证书组织名 |
| `CA_OU` | TSA | CA 证书部门 |
| `CA_CN` | TSA Root CA | CA 证书通用名 |
| `TSA_CN` | TSA Server | TSA 证书通用名 |
| `CERT_DAYS` | 3650 | 证书有效期 (天) |
| `TSA_POLICY_OID` | 1.2.3.4.1 | TSA 策略 OID |
| `NTP_SERVER1` | ntp.aliyun.com | NTP 服务器 1 |
| `NTP_SERVER2` | ntp.tencent.com | NTP 服务器 2 |
| `NTP_SERVER3` | cn.pool.ntp.org | NTP 服务器 3 |
| `LOG_LEVEL` | info | 日志级别 |

---

## 7. 国密证书管理

### 7.1 证书生成流程

证书在容器首次启动时由 `entrypoint.sh` 自动调用 `generate_certs.sh` 生成。流程如下:

```
1. 生成 SM2 CA 根私钥
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out cakey.pem

2. 生成 SM2 CA 自签名证书
   openssl req -new -x509 -key cakey.pem -out cacert.pem -sm3 -days 3650

3. 生成 SM2 TSA 私钥
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out tsakey.pem

4. 生成 TSA 证书签名请求
   openssl req -new -key tsakey.pem -out tsacsr.pem -sm3

5. 用 CA 签发 TSA 证书 (含 timeStamping EKU)
   openssl x509 -req -in tsacsr.pem -CA cacert.pem -CAkey cakey.pem -out tsacert.pem -sm3

6. 初始化序列号文件
   echo "01" > tsaserial
```

### 7.2 证书文件说明

| 文件 | 说明 | 权限 |
|------|------|------|
| cakey.pem | CA 根私钥 (SM2) | 600 |
| cacert.pem | CA 根证书 | 644 |
| tsakey.pem | TSA 签名私钥 (SM2) | 600 |
| tsacert.pem | TSA 签名证书 | 644 |
| tsaserial | TSA 序列号文件 | 644 |

### 7.3 手动查看证书

```bash
# 查看 TSA 证书详情
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/tsacert.pem -text -noout

# 查看 CA 证书详情
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/cacert.pem -text -noout

# 验证证书链
docker exec tsa /usr/local/tongsuo/bin/openssl verify -CAfile /etc/tsa/certs/cacert.pem /etc/tsa/certs/tsacert.pem

# 查看证书过期时间
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/tsacert.pem -noout -enddate
```

### 7.4 导出证书 (供客户端使用)

```bash
# 导出 TSA 证书
docker cp tsa:/etc/tsa/certs/tsacert.pem ./tsacert.pem

# 导出 CA 证书
docker cp tsa:/etc/tsa/certs/cacert.pem ./cacert.pem

# 也可以通过 HTTP 下载 (推荐)
curl http://localhost:8080/tsa/cert -o tsacert.pem
curl http://localhost:8080/tsa/cacert -o cacert.pem
```

### 7.5 证书生命周期管理

#### 查看证书过期时间

```bash
# 查看 TSA 证书过期时间
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/tsacert.pem -noout -enddate

# 查看 CA 证书过期时间
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/cacert.pem -noout -enddate
```

#### 备份证书

```bash
# 备份所有证书到本地
mkdir -p ./cert-backups
docker cp tsa:/etc/tsa/certs/cakey.pem ./cert-backups/
docker cp tsa:/etc/tsa/certs/cacert.pem ./cert-backups/
docker cp tsa:/etc/tsa/certs/tsakey.pem ./cert-backups/
docker cp tsa:/etc/tsa/certs/tsacert.pem ./cert-backups/
docker cp tsa:/etc/tsa/certs/tsaserial ./cert-backups/
```

#### 重新生成证书 (全新)

```bash
# 停止服务并删除数据卷 (会丢失现有证书和序列号)
docker compose down -v

# 重新启动 (自动生成新证书)
docker compose up --build -d
```

> **警告**: 重新生成证书后，旧证书签发的时间戳将无法用新 CA 验证。如需兼容旧时间戳，请保留旧 CA 证书备份。

#### 证书续期流程

当证书即将过期时，有两种处理方式:

**方式一: 重新生成 (简单，但不兼容旧时间戳)**

```bash
docker compose down -v
docker compose up --build -d
```

**方式二: 保留 CA，仅更换 TSA 证书 (推荐，兼容旧时间戳)**

```bash
# 1. 备份现有 CA
docker cp tsa:/etc/tsa/certs/cakey.pem ./cakey.pem.bak
docker cp tsa:/etc/tsa/certs/cacert.pem ./cacert.pem.bak

# 2. 生成新的 TSA 私钥和证书请求
docker exec tsa /usr/local/tongsuo/bin/openssl genpkey \
  -algorithm EC -pkeyopt ec_paramgen_curve:SM2 \
  -out /etc/tsa/certs/tsakey_new.pem

docker exec tsa /usr/local/tongsuo/bin/openssl req -new \
  -key /etc/tsa/certs/tsakey_new.pem \
  -out /etc/tsa/certs/tsacsr_new.pem -sm3

# 3. 用旧 CA 签发新 TSA 证书
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -req \
  -in /etc/tsa/certs/tsacsr_new.pem \
  -CA /etc/tsa/certs/cacert.pem -CAkey /etc/tsa/certs/cakey.pem \
  -out /etc/tsa/certs/tsacert_new.pem -sm3 -days 3650

# 4. 替换证书
docker exec tsa mv /etc/tsa/certs/tsakey_new.pem /etc/tsa/certs/tsakey.pem
docker exec tsa mv /etc/tsa/certs/tsacert_new.pem /etc/tsa/certs/tsacert.pem
docker exec tsa chown fcgiwrap:fcgiwrap /etc/tsa/certs/tsakey.pem /etc/tsa/certs/tsacert.pem
docker exec tsa chmod 600 /etc/tsa/certs/tsakey.pem

# 5. 重启容器
docker compose restart tsa
```

> **提示**: 无论哪种方式，已签发的时间戳 Token 内嵌了当时的证书，验证接口 `/api/tsa/verify` 会自动从 Token 提取证书验证，因此旧时间戳始终可验证。

### 7.6 使用自定义证书

如果要使用正式 CA 签发的证书:

```bash
# 1. 将证书文件拷贝进容器
docker cp your_tsacert.pem tsa:/etc/tsa/certs/tsacert.pem
docker cp your_tsakey.pem tsa:/etc/tsa/certs/tsakey.pem
docker cp your_cacert.pem tsa:/etc/tsa/certs/cacert.pem

# 2. 设置权限
docker exec tsa chmod 600 /etc/tsa/certs/tsakey.pem
docker exec tsa chmod 644 /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem
docker exec tsa chown fcgiwrap:fcgiwrap /etc/tsa/certs/tsakey.pem /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem

# 3. 重启容器
docker compose restart tsa
```

---

## 8. TSA Server 详解

### 8.1 Tongsuo

Tongsuo (铜锁) 是蚂蚁集团维护的国密密码库，兼容 OpenSSL API，支持 SM2/SM3/SM4 等国密算法。

**Dockerfile 中的构建过程:**

```dockerfile
# 从 GitHub 克隆
git clone --depth 1 https://github.com/Tongsuo-Project/Tongsuo.git /tmp/tongsuo

# Configure 编译
cd /tmp/tongsuo
./Configure --prefix=/usr/local/tongsuo --libdir=lib64
make -j$(nproc)
make install
ldconfig
```

**验证安装:**
```bash
docker exec tsa /usr/local/tongsuo/bin/openssl version
# 输出: Tongsuo 8.x ...
```

### 8.2 CGI 脚本 (tsa_cgi.sh)

核心时间戳处理脚本。工作流程:

```
1. 检查 HTTP 方法 (仅允许 POST)
2. 检查 Content-Length
3. 从 stdin 读取二进制 DER 数据 (TimeStampReq)
4. 调用 openssl ts -reply 生成时间戳 (Tongsuo)
5. 返回 DER 编码的 TimeStampResp
```

**关键命令:**
```bash
/usr/local/tongsuo/bin/openssl ts -reply \
    -queryfile "${QUERY_FILE}" \    # 输入的 TimeStampReq
    -signer /etc/tsa/certs/tsacert.pem \  # TSA 证书 (SM2)
    -inkey /etc/tsa/certs/tsakey.pem \    # TSA 私钥 (SM2)
    -md sm3 \                        # 摘要算法 (国密 SM3)
    -chain /etc/tsa/certs/cacert.pem \    # CA 证书链
    -config /etc/tsa/tsa.cnf \            # 配置文件
    -section tsa \                   # TSA 配置节
    -out "${RESP_FILE}"              # 输出 TimeStampResp
```

### 8.3 fcgiwrap

fcgiwrap 是一个简单的 CGI 到 FastCGI 的适配器。

**启动命令:**
```bash
spawn-fcgi \
    -p 9000 \              # 监听端口
    -u fcgiwrap \          # 运行用户
    -f /usr/sbin/fcgiwrap # CGI 包装器
```

### 8.4 TSA 配置文件 (tsa.cnf)

> **注意**: Tongsuo 的 `openssl ts -reply -section tsa` 不会跟随 `default_tsa` 指针到子段查找参数，因此所有配置必须直接写在 `[tsa]` 段内。

```ini
[tsa]
default_tsa = tsa_config1
dir = /etc/tsa
serial = $dir/certs/tsaserial         # 序列号文件
signer_cert = $dir/certs/tsacert.pem  # TSA 证书
signer_key = $dir/certs/tsakey.pem    # TSA 私钥
certs = $dir/certs/cacert.pem         # CA 证书
default_policy = 1.2.3.4.1           # 默认策略 OID
other_policies = 1.2.3.4.5
digests = sm3, SM3, sha256, sha384, sha512
accuracy = secs:1, millisecs:500, microsecs:0
clock_precision_digits = 0
ordering = yes
tsa_name = yes
ess_cert_id_chain = no
signer_digest = sm3                   # 签名摘要算法 (SM3)
```

### 8.5 日志查看

```bash
# TSA CGI 访问日志
docker exec tsa cat /var/log/tsa/tsa_cgi.log

# TSA CGI 错误日志
docker exec tsa cat /var/log/tsa/tsa_error.log
```

### 8.6 Supervisor 进程管理

容器内所有进程由 supervisor 托管，自动重启崩溃的进程。

**进程列表:**

| 进程 | 优先级 | 说明 |
|------|--------|------|
| chronyd | 10 | NTP 时间同步 |
| fcgiwrap | 20 | FastCGI 服务 (127.0.0.1:9000) |
| nginx | 30 | HTTP/HTTPS 反向代理 |
| tsa-demo | 40 | Demo REST API (127.0.0.1:9090) |

**常用命令:**

```bash
# 查看所有进程状态
docker exec tsa supervisorctl status

# 重启单个进程
docker exec tsa supervisorctl restart nginx
docker exec tsa supervisorctl restart fcgiwrap

# 查看某个进程的日志
docker exec tsa cat /var/log/supervisor/tsa-demo.err.log
```

---

## 9. Nginx 反向代理配置

### 9.1 核心配置

nginx 将 `/tsa` 路径的 POST 请求转发到 fcgiwrap:

```nginx
location = /tsa {
    limit_except POST { deny all; }  # 仅允许 POST
    
    fastcgi_pass 127.0.0.1:9000;     # 转发到容器内 fcgiwrap
    fastcgi_param SCRIPT_FILENAME /var/www/tsa/tsa_cgi.sh;
    fastcgi_param REQUEST_METHOD $request_method;
    fastcgi_param CONTENT_LENGTH $content_length;
    # ... 其他 FastCGI 参数
}
```

同时将 `/api` 路径反代到 tsa-demo:

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:9090/api/;
}
```

### 9.2 端点列表

#### TSA 核心端点 (nginx 直接处理)

| 路径 | 方法 | 端口 | 说明 |
|------|------|------|------|
| `/tsa` | POST | 80/443 | RFC 3161 时间戳请求 (FastCGI → CGI) |
| `/tsa/cert` | GET | 80/443 | 下载 TSA 签名证书 |
| `/tsa/cacert` | GET | 80/443 | 下载 CA 根证书 |
| `/health` | GET | 80/443 | 健康检查 (返回 `OK`) |
| `/info` | GET | 80/443 | 服务信息 (JSON) |

#### Demo API 端点 (nginx 反代到 tsa-demo :9090)

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/sm3/hash` | GET/POST | SM3 摘要计算 |
| `/api/sm2/keypair` | GET | 生成 SM2 密钥对 |
| `/api/sm2/sign` | POST | SM2 数字签名 |
| `/api/sm2/verify` | POST | SM2 签名验证 |
| `/api/sm2/encrypt` | POST | SM2 加密 |
| `/api/sm2/decrypt` | POST | SM2 解密 |
| `/api/tsa/timestamp` | POST | 对 Base64 数据请求时间戳 |
| `/api/tsa/timestamp/text` | POST | 对文本请求时间戳 |
| `/api/tsa/timestamp/sm3` | POST | 先算 SM3 摘要再请求时间戳 |
| `/api/tsa/verify` | POST | 验证时间戳令牌 (自动提取证书) |

### 9.3 HTTPS 配置

nginx 同时监听 443 端口提供 HTTPS，配置与 HTTP 80 端口完全相同的端点:

```nginx
server {
    listen 443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/tls/tls_cert.pem;
    ssl_certificate_key /etc/nginx/tls/tls_key.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:...;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    # 与 HTTP 相同的 location 配置...
}
```

### 9.4 证书体系说明

项目中有 **两套独立的证书**，用途完全不同:

| 证书类型 | 算法 | 用途 | 存储位置 | 谁使用 |
|---------|------|------|---------|--------|
| **SM2 CA/TSA 证书** | SM2 | 时间戳令牌签名 (业务层) | `/etc/tsa/certs/` | CGI 脚本 (openssl ts) |
| **TLS 证书** | RSA/EC | HTTPS 传输层加密 | `/etc/nginx/tls/` | nginx (ssl 指令) |

- SM2 证书由 `generate_certs.sh` 生成，包含 CA 根证书 + TSA 签名证书
- TLS 证书由 `generate_tls_certs.sh` 生成，为自签名 RSA 证书
- 更换 SM2 证书不影响 HTTPS；更换 TLS 证书不影响时间戳签名

---

## 10. Chrony 时间同步

### 10.1 配置说明

```ini
# NTP 上游服务器 (国内)
server ntp.aliyun.com iburst
server ntp.tencent.com iburst
server cn.pool.ntp.org iburst

# 允许时钟跳跃
makestep 1.0 3

# 禁用 UDP 命令端口 (仅通过 Unix socket 通信)
cmdport 0
```

### 10.2 查看同步状态

```bash
# 查看时间同步状态 (通过 Unix socket)
docker exec tsa chronyc -h /run/chrony/chronyd.sock -c tracking

# 预期输出:
# Reference ID    : CA801234 (ntp.aliyun.com)
# Stratum         : 3
# System time     : 0.000123456 seconds fast of NTP time
# Last offset     : +0.000012345 seconds
# RMS offset      : 0.000023456 seconds
```

```bash
# 查看 NTP 源
docker exec tsa chronyc -h /run/chrony/chronyd.sock -c sources
```

> **注意**: 容器内 chrony 配置了 `cmdport 0`，禁用了 UDP 命令端口，只能通过 Unix socket `/run/chrony/chronyd.sock` 通信。

---

## 11. Java SDK 使用指南

### 11.1 安装 SDK

#### 方式一: 本地编译安装

```bash
# 进入 SDK 目录
cd sdk

# 编译并安装到本地 Maven 仓库
mvn clean install -DskipTests
```

#### 方式二: 在项目中引入依赖

在 `pom.xml` 中添加:

```xml
<dependency>
    <groupId>com.tsa</groupId>
    <artifactId>tsa-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 11.2 配置 SDK

在 `application.yml` 中配置:

```yaml
tsa:
  url: http://localhost:8080/tsa    # TSA 服务器地址
  connect-timeout: 5000             # 连接超时 (毫秒)
  read-timeout: 30000               # 读取超时 (毫秒)
  policy-oid: 1.2.3.4.1             # TSA 策略 OID
  cert-req: true                    # 请求证书
  hash-algorithm: SM3               # 摘要算法
  auto-register-provider: true       # 自动注册 BC Provider
```

### 11.3 SM3 使用示例

```java
import com.tsa.starter.sm3.Sm3Util;

// 计算字符串的 SM3 摘要 (十六进制)
String hash = Sm3Util.hashHex("Hello, TSA!");
// 输出: 06c3a6f5e3a8e3a6...

// 计算字节数组的 SM3 摘要
byte[] data = "test".getBytes(StandardCharsets.UTF_8);
byte[] hashBytes = Sm3Util.hash(data);

// 计算 Base64 编码的摘要
String hashBase64 = Sm3Util.hashBase64("Hello");

// 流式计算 (大文件)
try (InputStream is = new FileInputStream("large_file.pdf")) {
    byte[] fileHash = Sm3Util.hash(is);
    String fileHashHex = Sm3Util.toHex(fileHash);
}
```

### 11.4 SM2 使用示例

```java
import com.tsa.starter.sm2.Sm2Util;
import java.security.KeyPair;

// 1. 生成 SM2 密钥对
KeyPair keyPair = Sm2Util.generateKeyPair();

// 2. 数字签名
byte[] data = "Hello, TSA!".getBytes(StandardCharsets.UTF_8);
byte[] signature = Sm2Util.sign(data, keyPair.getPrivate());

// 3. 验证签名
boolean valid = Sm2Util.verify(data, signature, keyPair.getPublic());
System.out.println("签名验证: " + valid); // true

// 4. 公钥加密
byte[] plaintext = "Secret message".getBytes();
byte[] ciphertext = Sm2Util.encrypt(plaintext, keyPair.getPublic());

// 5. 私钥解密
byte[] decrypted = Sm2Util.decrypt(ciphertext, keyPair.getPrivate());
System.out.println("解密结果: " + new String(decrypted)); // Secret message

// 6. 密钥序列化
String privHex = Sm2Util.privateKeyToHex(keyPair.getPrivate());
String pubHex = Sm2Util.publicKeyToHex(keyPair.getPublic());

// 7. 密钥反序列化
PrivateKey restoredPriv = Sm2Util.privateKeyFromHex(privHex);
PublicKey restoredPub = Sm2Util.publicKeyFromHex(pubHex);
```

### 11.5 TSA Client 使用示例

```java
import com.tsa.starter.TsaClient;
import com.tsa.starter.TsaProperties;
import com.tsa.starter.model.TimeStampResult;

// 方式一: 手动创建
TsaProperties props = new TsaProperties();
props.setUrl("http://localhost:8080/tsa");
TsaClient client = new TsaClient(props);

// 方式二: Spring Boot 自动注入
@Autowired
private TsaClient tsaClient;

// 1. 对字符串打时间戳
TimeStampResult result = tsaClient.timestamp("Hello, TSA!");

// 2. 对字节数据打时间戳
byte[] data = readFile("document.pdf");
TimeStampResult result = tsaClient.timestamp(data);

// 3. 对输入流打时间戳 (大文件)
try (InputStream is = new FileInputStream("large.pdf")) {
    TimeStampResult result = tsaClient.timestamp(is);
}

// 4. 使用预计算的 SM3 摘要打时间戳
byte[] hash = Sm3Util.hash(data);
TimeStampResult result = tsaClient.timestampWithSm3Hash(hash);

// 5. 获取结果信息
System.out.println("序列号: " + result.getSerialNumberHex());
System.out.println("生成时间: " + result.getGenTime());
System.out.println("策略OID: " + result.getPolicyOid());
System.out.println("令牌(Base64): " + result.getTimeStampTokenBase64());

// 6. 验证时间戳 (需要 TSA 证书)
X509Certificate tsaCert = tsaClient.loadCertificate(
    new FileInputStream("tsacert.pem")
);
boolean verified = tsaClient.verifyTimestamp(result, tsaCert);
System.out.println("验证结果: " + verified);
```

### 11.6 SDK 完整类参考

| 类 | 方法 | 说明 |
|---|------|------|
| **Sm3Util** | `hash(byte[])` | 计算SM3摘要 |
| | `hashHex(String)` | 计算SM3摘要(十六进制) |
| | `hashBase64(String)` | 计算SM3摘要(Base64) |
| | `hash(InputStream)` | 流式计算SM3摘要 |
| **Sm2Util** | `generateKeyPair()` | 生成SM2密钥对 |
| | `sign(byte[], PrivateKey)` | SM2签名 |
| | `verify(byte[], byte[], PublicKey)` | SM2验签 |
| | `encrypt(byte[], PublicKey)` | SM2加密 |
| | `decrypt(byte[], PrivateKey)` | SM2解密 |
| | `privateKeyToHex(PrivateKey)` | 私钥转十六进制 |
| | `publicKeyFromHex(String)` | 十六进制转公钥 |
| **TsaClient** | `timestamp(byte[])` | 请求时间戳 |
| | `timestamp(String)` | 对字符串打时间戳 |
| | `timestamp(InputStream)` | 对流数据打时间戳 |
| | `timestampWithSm3Hash(byte[])` | 用预计算SM3摘要请求 |
| | `verifyTimestamp(result, cert)` | 验证时间戳令牌 |
| | `loadCertificate(InputStream)` | 加载X.509证书 |

---

## 12. Demo 应用 API 文档

### 12.1 Demo 应用说明

Demo 应用已内置于 All-in-One 容器中，编译为 GraalVM 原生二进制（无 JVM），随容器自动启动。

- 访问地址: `http://localhost:9090/api/...`
- 也可通过 nginx 反代: `http://localhost:8080/api/...`

如需本地开发 Demo:
```bash
cd sdk && mvn clean install -DskipTests
cd ../sdk-demo && mvn spring-boot:run
```

### 12.2 API 接口

#### SM3 摘要

```bash
# GET 请求
curl "http://localhost:9090/api/sm3/hash?text=Hello"

# POST 请求
curl -X POST http://localhost:9090/api/sm3/hash \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
```

**响应:**
```json
{
  "algorithm": "SM3",
  "input": "Hello, TSA!",
  "hashHex": "06c3a6f5e3a8e3a6...",
  "hashBase64": "BsOm9eOo46Y...",
  "digestSize": 32
}
```

#### SM2 密钥对生成

```bash
curl http://localhost:9090/api/sm2/keypair
```

**响应:**
```json
{
  "algorithm": "SM2",
  "curve": "sm2p256v1",
  "privateKeyHex": "3e2a8b...",
  "publicKeyHex": "04a3b5c7...",
  "publicKeyCompressedHex": "03a3b5c7..."
}
```

#### SM2 签名

```bash
# 不提供 privateKeyHex 时自动生成密钥对
curl -X POST http://localhost:9090/api/sm2/sign \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'

# 提供 privateKeyHex 使用指定私钥签名
curl -X POST http://localhost:9090/api/sm2/sign \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!", "privateKeyHex":"3e2a8b..."}'
```

**响应:**
```json
{
  "algorithm": "SM3withSM2",
  "input": "Hello, TSA!",
  "signatureBase64": "MEQCIB...",
  "privateKeyHex": "3e2a8b...",
  "publicKeyHex": "04a3b5c7..."
}
```

#### SM2 验签

```bash
curl -X POST http://localhost:9090/api/sm2/verify \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Hello, TSA!",
    "signatureBase64": "MEQCIB...",
    "publicKeyHex": "04a3b5c7..."
  }'
```

**响应:**
```json
{
  "algorithm": "SM3withSM2",
  "input": "Hello, TSA!",
  "valid": true
}
```

#### SM2 加密

```bash
# 不提供 publicKeyHex 时自动生成密钥对
curl -X POST http://localhost:9090/api/sm2/encrypt \
  -H "Content-Type: application/json" \
  -d '{"text":"Secret message"}'
```

**响应:**
```json
{
  "algorithm": "SM2",
  "input": "Secret message",
  "ciphertextBase64": "...",
  "privateKeyHex": "3e2a8b...",
  "note": "Auto-generated keypair. Use privateKeyHex to decrypt."
}
```

#### SM2 解密

```bash
curl -X POST http://localhost:9090/api/sm2/decrypt \
  -H "Content-Type: application/json" \
  -d '{
    "ciphertextBase64": "...",
    "privateKeyHex": "3e2a8b..."
  }'
```

**响应:**
```json
{
  "algorithm": "SM2",
  "plaintext": "Secret message"
}
```

#### TSA 时间戳请求

```bash
# 对文本打时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'

# 对 Base64 数据打时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp \
  -H "Content-Type: application/json" \
  -d '{"dataBase64":"SGVsbG8sIFRTQQ=="}'

# 对文本先计算 SM3 摘要再打时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/sm3 \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
```

**响应 (`/api/tsa/timestamp/text` 和 `/api/tsa/timestamp`):**
```json
{
  "success": true,
  "status": 0,
  "statusString": "Status: Granted",
  "serialNumber": "01",
  "genTime": "Mon Jul 21 10:30:00 CST 2026",
  "policyOid": "1.2.3.4.1",
  "hashAlgorithmOid": "1.2.156.10197.1.401",
  "messageImprintHex": "06c3a6f5e3a8e3a6...",
  "tokenBase64": "MIIF...",
  "responseBase64": "MIIF...",
  "tokenSize": 2048,
  "input": "Hello, TSA!"
}
```

**响应 (`/api/tsa/timestamp/sm3`，额外返回 SM3 摘要):**
```json
{
  "success": true,
  "status": 0,
  "statusString": "Status: Granted",
  "serialNumber": "02",
  "genTime": "Mon Jul 21 10:31:00 CST 2026",
  "policyOid": "1.2.3.4.1",
  "hashAlgorithmOid": "1.2.156.10197.1.401",
  "messageImprintHex": "06c3a6f5e3a8e3a6...",
  "tokenBase64": "MIIF...",
  "responseBase64": "MIIF...",
  "tokenSize": 2048,
  "input": "Hello, TSA!",
  "sm3HashHex": "06c3a6f5e3a8e3a6..."
}
```

#### TSA 时间戳验证

自动从 Token 内部提取签名证书进行验证，无需外部传入证书。同时验证签名有效性和数据摘要一致性。

```bash
curl -X POST http://localhost:9090/api/tsa/verify \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Hello, TSA!",
    "responseBase64": "MIIF..."
  }'
```

**响应:**
```json
{
  "valid": true,
  "signatureValid": true,
  "hashMatch": true,
  "certSubject": "CN=TSA Server",
  "certExpiry": "Sun Jul 20 10:30:00 CST 2036",
  "expectedHashHex": "06c3a6f5e3a8e3a6...",
  "tokenHashHex": "06c3a6f5e3a8e3a6...",
  "serialNumber": "01",
  "genTime": "Mon Jul 21 10:30:00 CST 2026",
  "policyOid": "1.2.3.4.1",
  "input": "Hello, TSA!"
}
```

> **说明**: `valid` = `signatureValid` && `hashMatch`。当证书续期/更换后，旧时间戳仍可自动验证，因为验证证书从 Token 内嵌提取。

---

## 13. 测试与验证

### 13.1 使用 curl 测试 TSA

```bash
# 1. 使用 Tongsuo 生成时间戳请求
# (需要在安装了 Tongsuo 的环境中执行)
/usr/local/tongsuo/bin/openssl ts -query -data "test.txt" -sm3 -no_nonce -out query.tsq

# 2. 发送请求到 TSA
curl -X POST http://localhost:8080/tsa \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @query.tsq \
  -o response.tsr

# 3. 查看响应
/usr/local/tongsuo/bin/openssl ts -reply -in response.tsr -text
```

### 13.2 使用 OpenSSL 测试 (非国密)

```bash
# 1. 生成 SHA256 请求 (测试兼容性)
openssl ts -query -data "test.txt" -sha256 -no_nonce -out query_sha256.tsq

# 2. 发送请求
curl -X POST http://localhost:8080/tsa \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @query_sha256.tsq \
  -o response_sha256.tsr

# 3. 验证时间戳
openssl ts -verify -in response_sha256.tsr \
  -queryfile query_sha256.tsq \
  -CAfile cacert.pem \
  -untrusted tsacert.pem
```

### 13.3 使用 Demo API 测试

```bash
# 1. 启动 TSA 服务
docker compose up --build -d

# 2. 等待服务就绪 (约30秒)
sleep 30

# 3. 测试 SM3
curl "http://localhost:9090/api/sm3/hash?text=Hello"

# 4. 测试时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'

# 5. 验证时间戳
curl -X POST http://localhost:9090/api/tsa/verify \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!", "responseBase64":"<上一步返回的responseBase64>"}'
```

### 13.4 完整自测脚本

```bash
#!/bin/bash
# test_tsa.sh - TSA 完整测试脚本

TSA_URL="http://localhost:8080/tsa"
DEMO_URL="http://localhost:9090"

echo "=== TSA Server 测试 ==="
echo ""

# 1. 健康检查
echo "1. 健康检查..."
HEALTH=$(curl -s http://localhost:8080/health)
echo "   结果: $HEALTH"
echo ""

# 2. 服务信息
echo "2. 服务信息..."
INFO=$(curl -s http://localhost:8080/info)
echo "   $INFO"
echo ""

# 3. SM3 摘要测试
echo "3. SM3 摘要测试..."
SM3=$(curl -s "http://localhost:9090/api/sm3/hash?text=Hello%20TSA")
echo "   $SM3"
echo ""

# 4. SM2 密钥对生成测试
echo "4. SM2 密钥对生成测试..."
KEYPAIR=$(curl -s http://localhost:9090/api/sm2/keypair)
echo "   $KEYPAIR" | head -c 200
echo "..."
echo ""

# 5. 时间戳请求测试
echo "5. 时间戳请求测试..."
TS=$(curl -s -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}')
echo "   $TS" | head -c 300
echo "..."
echo ""

echo "=== 测试完成 ==="
```

---

## 14. 生产部署最佳实践

### 14.1 安全建议

1. **使用正式 CA 证书**: 不要在生产环境使用自签名 CA
2. **启用 HTTPS**: 使用 443 端口而非 80
3. **私钥保护**: TSA 私钥应存储在 HSM 或 KMS 中
4. **网络隔离**: TSA 服务器不应直接暴露公网
5. **访问控制**: 在 nginx 中配置 IP 白名单
6. **日志审计**: 记录所有时间戳请求
7. **证书轮换**: 定期更新 TSA 证书

### 14.2 性能优化

```nginx
# nginx 性能优化
worker_processes auto;
worker_connections 4096;
multi_accept on;

# FastCGI 缓存
fastcgi_buffer_size 128k;
fastcgi_buffers 4 256k;
fastcgi_busy_buffers_size 256k;
```

### 14.3 高可用部署

```
                    ┌─── nginx (LB) ───┐
                    │                   │
              ┌─────┴─────┐      ┌─────┴─────┐
              │  tsa-1    │      │  tsa-2    │
              │ (active)  │      │ (standby) │
              └─────┬─────┘      └─────┬─────┘
                    │                   │
              ┌─────┴─────┐      ┌─────┴─────┐
              │  chrony   │      │  chrony   │
              └───────────┘      └───────────┘

共享存储: NFS/分布式存储 (证书 + 序列号)
```

### 14.4 监控

```bash
# 监控容器资源
docker stats tsa

# 监控 NTP 偏差
docker exec tsa chronyc -h /run/chrony/chronyd.sock -c tracking

# 监控 TSA 证书过期时间
docker exec tsa /usr/local/tongsuo/bin/openssl x509 -in /etc/tsa/certs/tsacert.pem -noout -enddate

# 查看 supervisor 进程状态
docker exec tsa supervisorctl status
```

---

## 15. 故障排除

### 15.1 常见问题

#### Q: 构建失败 — Tongsuo 编译错误

```bash
# 检查 Docker 内存限制
docker info | grep "Total Memory"

# 建议: 至少分配 2GB 内存给 Docker
# Windows: Docker Desktop → Settings → Resources → Memory: 4GB
```

#### Q: nginx 返回 502 Bad Gateway

```bash
# 检查 fcgiwrap 是否在运行
docker exec tsa pgrep fcgiwrap

# 检查端口 9000
docker exec tsa ss -tlnp | grep 9000

# 查看 supervisor 进程状态
docker exec tsa supervisorctl status

# 查看容器日志
docker logs tsa
```

#### Q: TSA 返回 500 — 时间戳生成失败

```bash
# 查看错误日志
docker exec tsa cat /var/log/tsa/tsa_error.log

# 检查证书是否存在
docker exec tsa ls -la /etc/tsa/certs/

# 检查序列号文件
docker exec tsa cat /etc/tsa/certs/tsaserial

# 手动测试 openssl ts 命令
docker exec tsa /usr/local/tongsuo/bin/openssl ts -reply \
  -queryfile /tmp/test.tsq \
  -signer /etc/tsa/certs/tsacert.pem \
  -inkey /etc/tsa/certs/tsakey.pem \
  -md sm3 \
  -config /etc/tsa/tsa.cnf \
  -section tsa
```

#### Q: chrony 同步失败

```bash
# 检查 chrony 状态 (通过 Unix socket)
docker exec tsa chronyc -h /run/chrony/chronyd.sock -c tracking

# 检查 NTP 源
docker exec tsa chronyc -h /run/chrony/chronyd.sock -c sources

# 如果 NTP 不可达，检查网络
docker exec tsa ping ntp.aliyun.com
```

#### Q: CGI 脚本 Permission denied

```bash
# 检查日志目录权限
docker exec tsa ls -la /var/log/tsa/

# 修复权限 (fcgiwrap 用户需要写权限)
docker exec tsa chown -R fcgiwrap:fcgiwrap /var/log/tsa

# 检查 CGI 脚本权限
docker exec tsa ls -la /var/www/tsa/tsa_cgi.sh
```

#### Q: 容器启动后证书未生成

```bash
# 检查 entrypoint.sh 是否执行了证书生成
docker logs tsa | grep -i "cert\|generate"

# 手动执行证书生成
docker exec tsa /scripts/generate_certs.sh

# 检查证书目录
docker exec tsa ls -la /etc/tsa/certs/
```

#### Q: supervisor 进程异常退出

```bash
# 查看 supervisor 状态
docker exec tsa supervisorctl status

# 查看具体进程日志
docker exec tsa cat /var/log/supervisor/nginx.err.log
docker exec tsa cat /var/log/supervisor/fcgiwrap.err.log

# 手动重启某个进程
docker exec tsa supervisorctl restart nginx
```

#### Q: Java SDK 编译失败

```bash
# 确保 Java 21+
java -version

# 确保 Maven 3.8+
mvn -version

# 清理后重新编译
cd sdk
mvn clean install -DskipTests
```

#### Q: 时间戳验证失败

```bash
# 1. 检查 CA 证书链是否完整
docker exec tsa /usr/local/tongsuo/bin/openssl verify \
  -CAfile /etc/tsa/certs/cacert.pem \
  /etc/tsa/certs/tsacert.pem

# 2. 检查时间戳令牌内容
/usr/local/tongsuo/bin/openssl ts -reply -in response.tsr -text

# 3. 使用 Demo API 验证 (自动提取 Token 内嵌证书)
curl -X POST http://localhost:9090/api/tsa/verify \
  -H "Content-Type: application/json" \
  -d '{"text":"原文", "responseBase64":"<上一步的responseBase64>"}'

# 4. 如果签名无效，检查证书是否已更换
#    旧证书签发的时间戳需要用旧证书验证
#    /api/tsa/verify 会自动从 Token 内嵌提取证书，无需手动指定
```

### 15.2 调试模式

```bash
# 以调试模式启动 (前台运行)
docker compose up --build

# 进入容器调试
docker exec -it tsa bash

# 手动执行 CGI 脚本
docker exec -it tsa \
  REQUEST_METHOD=POST \
  CONTENT_LENGTH=100 \
  /var/www/tsa/tsa_cgi.sh < /tmp/test_request.bin
```

---

## 16. 附录: OID 与算法参考

### 16.1 国密算法 OID

| 算法 | OID | 说明 |
|------|-----|------|
| SM2 | 1.2.156.10197.1.301 | 椭圆曲线公钥密码 |
| SM3 | 1.2.156.10197.1.401 | 密码杂凑算法 |
| SM4 | 1.2.156.10197.1.104 | 分组密码（本 TSA 未使用） |
| SM2withSM3 | 1.2.156.10197.1.501 | SM2 签名 + SM3 摘要 |

### 16.2 RFC 3161 Content-Type

| 方向 | Content-Type |
|------|----------------|
| 请求 | `application/timestamp-query` |
| 响应 | `application/timestamp-reply` |

### 16.3 证书 EKU 要求

TSA 签名证书必须包含：

- `extendedKeyUsage = critical, timeStamping`（OID `1.3.6.1.5.5.7.3.8`）
- `keyUsage = digitalSignature`
- `basicConstraints = CA:false`

### 16.4 一键命令速查

```powershell
# 启动
docker compose up --build -d

# 状态 / 日志
docker compose ps
docker compose logs -f

# supervisor 进程状态
docker exec tsa supervisorctl status

# 证书
curl http://localhost:8080/tsa/cert -o tsacert.pem
curl http://localhost:8080/tsa/cacert -o cacert.pem

# 测试
curl "http://localhost:9090/api/sm3/hash?text=Hello"
curl -X POST http://localhost:9090/api/tsa/timestamp/text -H "Content-Type: application/json" -d '{"text":"Hello, TSA!"}'

# 自测脚本
pwsh ./scripts/test_tsa.ps1
```

---

## 17. GitHub Actions 镜像构建

### 17.1 工作流一览

| 文件 | 名称 | 说明 |
|------|------|------|
| `.github/workflows/docker-build.yml` | Docker Build & Push | 构建 **1 个** All-in-One 多架构镜像 `.../tsa` |
| `.github/workflows/maven-ci.yml` | Maven CI | 编译 SDK / Demo，上传 jar |
| `.github/workflows/ci.yml` | CI | 路径变更检测与总览 |

**镜像地址：** `ghcr.io/<owner>/<repo>/tsa:<tag>`

### 17.2 Docker 构建行为

| 事件 | 是否构建 | 是否推送 GHCR |
|------|----------|---------------|
| `pull_request` | 是（双架构校验） | 否 |
| `push` 到 main/master/develop | 是（路径过滤） | 是（multi-arch） |
| `push` 标签 `v*` | 是 | 是（multi-arch） |
| `workflow_dispatch` | 是 | 可选（默认 true） |

**镜像名**（全小写）：

```text
ghcr.io/<owner>/<repo>/tsa
```

**多架构 (amd64 + arm64)**：

| 平台 | Runner | 用途 |
|------|--------|------|
| `linux/amd64` | `ubuntu-latest` | x86_64 服务器 |
| `linux/arm64` | `ubuntu-24.04-arm` | ARM64 / Apple Silicon 等 |

构建流程：各架构**原生编译** → 按 digest 推送 → `docker buildx imagetools create` 合并 multi-arch manifest 并打业务标签。  
不使用 QEMU 模拟交叉编译（Tongsuo 源码编译在 QEMU 下极易超时）。

**常用标签**：`latest`、分支名、`sha-<short>`、语义化版本（由 `v1.2.3` 标签生成）。

**缓存**：Buildx `type=gha`，按 `镜像名-架构` 分 scope。  
**超时**：`tsa` 120 分钟。

**检查架构**：

```bash
docker buildx imagetools inspect ghcr.io/<owner>/<repo>/tsa:latest
# 应看到 Platform: linux/amd64 与 linux/arm64
```

### 17.3 手动触发

GitHub 仓库 → **Actions** → **Docker Build & Push** → **Run workflow**：

- `push_images`：是否推送
- `image_filter`：`all` / `tsa`

### 17.4 拉取预构建镜像部署

```bash
# 私有 Package 需登录
echo "$GITHUB_TOKEN" | docker login ghcr.io -u YOUR_USER --password-stdin

export GHCR_OWNER=your-org    # 小写
export GHCR_REPO=shijianchuo  # 小写
export IMAGE_TAG=latest

docker compose -f docker-compose.ghcr.yml pull
docker compose -f docker-compose.ghcr.yml up -d
```

### 17.5 Package 权限

1. 推送使用 `permissions.packages: write` + `GITHUB_TOKEN`，无需自建 Secret。
2. 若组织启用了限制策略，在 **Organization → Packages** 允许 Actions 写入。
3. 对外公开镜像：Packages → 对应镜像 → Package settings → Change visibility → Public。

### 17.6 Maven CI

```text
触发: sdk/**、sdk-demo/**、pom.xml 变更
步骤: setup-java(Temurin 21) → mvn clean install → 上传 jar artifact
```

产物在 Actions 运行页 **Artifacts** 中下载，保留 14 天。

---

**文档结束** · 版本 3.0.0 · 工程路径以本仓库根目录为准。

> 如有疑问请检查故障排除章节或查看容器日志。
