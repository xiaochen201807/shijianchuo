# RFC 3161 国密 TSA 服务器 — 完整操作手册

> **版本**: 1.0.0  
> **日期**: 2026-07-21  
> **适用场景**: 电子签名、存证、时间戳服务  
> **加密算法**: SM2 (签名) + SM3 (摘要) 国密算法  
> **部署方案**: Docker Compose (GmSSL3 + nginx + fcgiwrap + chrony)

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

本项目使用 **GmSSL3** 库提供以下国密算法：

| 算法 | 类型 | 用途 | OID |
|------|------|------|-----|
| SM2 | 非对称加密/签名 | TSA 证书签名、时间戳令牌签名 | 1.2.156.10197.1.301 |
| SM3 | 密码杂凑 | 消息摘要、时间戳请求摘要 | 1.2.156.10197.1.401 |

### 1.3 核心特性

- ✅ RFC 3161 时间戳协议完整实现
- ✅ SM2 椭圆曲线数字签名
- ✅ SM3 密码杂凑算法
- ✅ GmSSL3 国密密码库
- ✅ nginx + fcgiwrap 生产级部署
- ✅ chrony NTP 时间同步
- ✅ Docker Compose 一键部署
- ✅ Java Spring Boot Starter SDK
- ✅ 自动生成 SM2 CA 和 TSA 证书

---

## 2. 系统架构

### 2.1 架构图

```
┌──────────────────────────────────────────────────────────────┐
│                     Docker Compose 网络                       │
│                                                              │
│  ┌─────────────┐    ┌──────────────────┐    ┌─────────────┐ │
│  │   chrony    │    │   tsa-server     │    │    nginx    │ │
│  │             │    │                  │    │             │ │
│  │  NTP 时间   │◄──►│  GmSSL3 +        │◄──►│  反向代理   │ │
│  │  同步服务   │    │  fcgiwrap +      │    │  HTTP/HTTPS │ │
│  │             │    │  CGI 脚本        │    │             │ │
│  │  Port: 123  │    │  Port: 9000      │    │  Port: 80  │ │
│  │   (UDP)     │    │   (FastCGI)      │    │  Port: 443  │ │
│  └─────────────┘    └──────────────────┘    └──────┬──────┘ │
│                                                     │        │
└─────────────────────────────────────────────────────┼────────┘
                                                      │
                                              ┌───────┴───────┐
                                              │   客户端       │
                                              │               │
                                              │ Java SDK     │
                                              │ curl / postman│
                                              └───────────────┘
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
                                         4. gmssl ts -reply                     │
                                         -signer tsacert.pem                    ▼
                                         -inkey tsakey.pem                ┌──────────┐
                                         -md sm3                           │  GmSSL3  │
                                         │                                 │ (SM2/SM3)│
                                         ▼                                 └──────────┘
                                  ┌──────────────┐
                                  │  DER 编码的   │
                                  │TimeStampResp │
                                  └──────────────┘
```

### 2.3 组件职责

| 组件 | 职责 | 技术 |
|------|------|------|
| **chrony** | NTP时间同步，确保TSA时间戳精确 | Alpine + chrony |
| **tsa-server** | 时间戳生成核心服务 | Ubuntu + GmSSL3 + fcgiwrap |
| **nginx** | HTTP/HTTPS反向代理 | nginx:1.25-alpine |
| **SDK** | Java客户端开发包 | Spring Boot 3.5.9 + BouncyCastle 1.81 |

---

## 3. 环境准备

### 3.1 系统要求

- **操作系统**: Windows 10/11, macOS, Linux
- **Docker**: 20.10+ (含 Docker Compose v2)
- **内存**: 2GB+ (推荐 4GB)
- **磁盘**: 2GB+ (GmSSL3编译需要空间)
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

编辑 `.env` 文件修改端口和证书信息:

```env
# 修改端口 (如果8080被占用)
NGINX_HTTP_PORT=8080
NGINX_HTTPS_PORT=8443

# 修改证书主题
CA_ORG=YourOrganization
TSA_CN=YourTSA
```

### 4.3 一键启动

```powershell
# Windows PowerShell
docker compose up --build -d
```

```bash
# Linux/macOS
docker compose up --build -d
```

**首次构建大约需要 10-15 分钟** (GmSSL3编译较慢)。

### 4.4 查看启动状态

```bash
# 查看所有容器状态
docker compose ps

# 预期输出:
# NAME         STATUS      PORTS
# tsa-chrony   Up (healthy)
# tsa-server   Up (healthy)
# tsa-nginx    Up (healthy)  0.0.0.0:8080->80/tcp, 0.0.0.0:8443->443/tcp
```

### 4.5 查看日志

```bash
# 查看所有服务日志
docker compose logs -f

# 仅查看 TSA Server 日志
docker compose logs -f tsa-server

# 仅查看 nginx 日志
docker compose logs -f nginx
```

### 4.6 快速测试

```bash
# 健康检查
curl http://localhost:8080/health
# 预期输出: OK

# 服务器信息
curl http://localhost:8080/info
# 预期输出: {"service":"TSA","version":"1.0","algorithms":["SM2","SM3"],"rfc":"3161"}
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
├── .env                              # 环境变量配置
├── docker-compose.yml                # Docker Compose 编排文件
│
├── tsa-server/                       # TSA Server (核心)
│   ├── Dockerfile                    # Docker 构建文件 (编译GmSSL3)
│   ├── config/
│   │   ├── tsa.cnf                   # GmSSL/OpenSSL TSA 配置
│   │   └── tsa_ext.cnf              # 证书扩展配置
│   └── scripts/
│       ├── entrypoint.sh             # 容器入口脚本
│       ├── generate_certs.sh         # SM2证书生成脚本
│       ├── tsa_cgi.sh                # CGI时间戳处理脚本
│       └── healthcheck.sh            # 健康检查脚本
│
├── nginx/                            # Nginx 反向代理
│   ├── Dockerfile                    # Nginx Docker 构建文件
│   ├── nginx.conf                    # Nginx 配置 (FastCGI代理)
│   ├── docker-entrypoint.sh          # 入口脚本 (生成TLS证书)
│   └── generate_tls_certs.sh         # TLS证书生成脚本
│
├── chrony/                           # Chrony NTP 时间同步
│   ├── Dockerfile                    # Chrony Docker 构建文件
│   └── chrony.conf                   # Chrony 配置
│
├── sdk/                              # Java Spring Boot Starter SDK
│   ├── pom.xml                       # Maven 配置
│   └── src/main/java/com/tsa/starter/
│       ├── TsaAutoConfiguration.java # Spring Boot 自动配置
│       ├── TsaClient.java            # TSA 客户端核心
│       ├── TsaProperties.java        # 配置属性类
│       ├── sm2/Sm2Util.java          # SM2 签名/加密工具
│       ├── sm3/Sm3Util.java           # SM3 摘要工具
│       ├── model/TimeStampResult.java # 时间戳结果模型
│       └── exception/TsaException.java # 异常类
│
├── sdk-demo/                         # Demo 示例应用
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/tsa/demo/
│       │   ├── DemoApplication.java  # Spring Boot 启动类
│       │   └── controller/
│       │       └── TsaDemoController.java  # REST API
│       └── resources/
│           └── application.yml       # 应用配置
│
├── docs/
│   └── operation-manual.md           # 本文档
│
├── docker-compose.ghcr.yml           # 使用 GHCR 预构建镜像部署
├── .env.example                      # 环境变量示例
└── .github/workflows/
    ├── docker-build.yml              # Docker 镜像构建推送 GHCR
    ├── maven-ci.yml                  # Java SDK 编译 CI
    └── ci.yml                        # 路径变更总览
```

---

## 6. Docker Compose 配置详解

### 6.1 服务定义

`docker-compose.yml` 定义了 3 个服务:

| 服务 | 镜像 | 端口 | 依赖 |
|------|------|------|------|
| chrony | 自建 (Alpine + chrony) | 123/UDP | 无 |
| tsa-server | 自建 (Ubuntu + GmSSL3 + fcgiwrap) | 9000 | chrony |
| nginx | 自建 (nginx:1.25-alpine) | 80, 443 | tsa-server |

### 6.2 数据卷

| 卷名 | 用途 | 挂载点 |
|------|------|--------|
| tsa-certs | TSA SM2 证书 | tsa: /etc/tsa/certs, nginx: /etc/nginx/tsa-certs |
| tsa-serial | TSA 序列号 | tsa: /var/lib/tsa |
| tsa-logs | TSA 日志 | tsa: /var/log/tsa |
| nginx-logs | Nginx 日志 | nginx: /var/log/nginx |
| chrony-data | Chrony 数据 | chrony: /var/lib/chrony |

### 6.3 网络

所有服务使用名为 `tsa-net` 的自定义桥接网络:

```yaml
networks:
  tsa-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.28.0.0/16
```

服务间通过别名通信:
- `tsa` → tsa-server
- `nginx` → nginx
- `chrony` → chrony

### 6.4 环境变量

编辑 `.env` 文件自定义配置:

```env
# 端口配置
NGINX_HTTP_PORT=8080     # HTTP 端口
NGINX_HTTPS_PORT=8443    # HTTPS 端口

# 证书主题
CA_COUNTRY=CN
CA_STATE=Beijing
CA_ORG=MyOrg
TSA_CN=TSA Server

# NTP 服务器
NTP_SERVER1=ntp.aliyun.com
NTP_SERVER2=ntp.tencent.com
```

---

## 7. 国密证书管理

### 7.1 证书生成流程

证书在 `tsa-server` 容器启动时自动生成。流程如下:

```
1. 生成 SM2 CA 根私钥
   gmssl ecparam -genkey -name sm2p256v1 -out cakey.pem

2. 生成 SM2 CA 自签名证书
   gmssl req -new -x509 -key cakey.pem -out cacert.pem -sm3 -days 3650

3. 生成 SM2 TSA 私钥
   gmssl ecparam -genkey -name sm2p256v1 -out tsakey.pem

4. 生成 TSA 证书签名请求
   gmssl req -new -key tsakey.pem -out tsacsr.pem -sm3

5. 用 CA 签发 TSA 证书 (含 timeStamping EKU)
   gmssl x509 -req -in tsacsr.pem -CA cacert.pem -CAkey cakey.pem -out tsacert.pem -sm3

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
docker exec tsa-server gmssl x509 -in /etc/tsa/certs/tsacert.pem -text -noout

# 查看 CA 证书详情
docker exec tsa-server gmssl x509 -in /etc/tsa/certs/cacert.pem -text -noout

# 验证证书链
docker exec tsa-server gmssl verify -CAfile /etc/tsa/certs/cacert.pem /etc/tsa/certs/tsacert.pem
```

### 7.4 导出证书 (供客户端使用)

```bash
# 导出 TSA 证书 (客户端验证时间戳时需要)
docker cp tsa-server:/etc/tsa/certs/tsacert.pem ./tsacert.pem

# 导出 CA 证书
docker cp tsa-server:/etc/tsa/certs/cacert.pem ./cacert.pem

# 也可以通过 HTTP 下载
curl http://localhost:8080/tsa/cert -o tsacert.pem
curl http://localhost:8080/tsa/cacert -o cacert.pem
```

### 7.5 重新生成证书

```bash
# 1. 停止服务并删除数据卷
docker compose down -v

# 2. 重新启动 (会自动生成新证书)
docker compose up --build -d
```

### 7.6 使用自定义证书

如果要使用正式 CA 签发的证书:

```bash
# 1. 将证书文件放入 Docker 卷
docker cp your_tsacert.pem tsa-server:/etc/tsa/certs/tsacert.pem
docker cp your_tsakey.pem tsa-server:/etc/tsa/certs/tsakey.pem
docker cp your_cacert.pem tsa-server:/etc/tsa/certs/cacert.pem

# 2. 设置权限
docker exec tsa-server chmod 600 /etc/tsa/certs/tsakey.pem
docker exec tsa-server chmod 644 /etc/tsa/certs/tsacert.pem /etc/tsa/certs/cacert.pem

# 3. 重启 TSA Server
docker compose restart tsa-server
```

---

## 8. TSA Server 详解

### 8.1 GmSSL3

GmSSL3 是支持国密算法的开源密码库，兼容 OpenSSL API。

**Dockerfile 中的构建过程:**

```dockerfile
# 从 GitHub 克隆
git clone --depth 1 https://github.com/guanzhi/GmSSL.git /tmp/GmSSL

# CMake 编译
cd /tmp/GmSSL && mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local
make -j$(nproc)
make install
ldconfig
```

**验证安装:**
```bash
docker exec tsa-server gmssl version
# 输出: GmSSL 3.1.x ...
```

### 8.2 CGI 脚本 (tsa_cgi.sh)

核心时间戳处理脚本。工作流程:

```
1. 检查 HTTP 方法 (仅允许 POST)
2. 检查 Content-Length
3. 从 stdin 读取二进制 DER 数据 (TimeStampReq)
4. 调用 gmssl ts -reply 生成时间戳
5. 返回 DER 编码的 TimeStampResp
```

**关键命令:**
```bash
gmssl ts -reply \
    -queryfile "${QUERY_FILE}" \    # 输入的 TimeStampReq
    -signer /etc/tsa/certs/tsacert.pem \  # TSA 证书 (SM2)
    -inkey /etc/tsa/certs/tsakey.pem \    # TSA 私钥 (SM2)
    -md sm3 \                        # 摘要算法 (国密 SM3)
    -chain /etc/tsa/certs/cacert.pem \    # CA 证书链
    -config /etc/tsa/openssl/tsa.cnf \    # 配置文件
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

```ini
[tsa]
default_tsa = tsa_config1

[tsa_config1]
dir = /etc/tsa
serial = $dir/certs/tsaserial        # 序列号文件
signer_cert = $dir/certs/tsacert.pem  # TSA 证书
signer_key = $dir/certs/tsakey.pem    # TSA 私钥
certs = $dir/certs/cacert.pem         # CA 证书
default_policy = 1.2.3.4.1           # 默认策略 OID
digests = sm3                         # 支持的摘要算法
```

### 8.5 日志查看

```bash
# TSA CGI 访问日志
docker exec tsa-server cat /var/log/tsa/tsa_cgi.log

# TSA CGI 错误日志
docker exec tsa-server cat /var/log/tsa/tsa_error.log
```

---

## 9. Nginx 反向代理配置

### 9.1 核心配置

nginx 将 `/tsa` 路径的 POST 请求转发到 fcgiwrap:

```nginx
location = /tsa {
    limit_except POST { deny all; }  # 仅允许 POST
    
    fastcgi_pass tsa_fcgi;           # 转发到 tsa:9000
    fastcgi_param SCRIPT_FILENAME /var/www/tsa/tsa_cgi.sh;
    fastcgi_param REQUEST_METHOD $request_method;
    fastcgi_param CONTENT_LENGTH $content_length;
    # ... 其他 FastCGI 参数
}
```

### 9.2 端点列表

| 路径 | 方法 | 说明 |
|------|------|------|
| `/tsa` | POST | RFC 3161 时间戳请求 |
| `/tsa/cert` | GET | 下载 TSA 证书 |
| `/tsa/cacert` | GET | 下载 CA 证书 |
| `/health` | GET | 健康检查 |
| `/info` | GET | 服务信息 |

### 9.3 HTTPS 配置

nginx 同时监听 443 端口提供 HTTPS:

```nginx
server {
    listen 443 ssl;
    
    ssl_certificate     /etc/nginx/tsa-certs/tls_cert.pem;
    ssl_certificate_key /etc/nginx/tsa-certs/tls_key.pem;
    
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:...;
}
```

> **注意**: HTTPS 的 TLS 证书与 TSA 的 SM2 签名证书是不同的。TLS 证书用于传输层加密，SM2 证书用于时间戳签名。

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

# 允许 Docker 网络查询
allow 172.28.0.0/16
```

### 10.2 查看同步状态

```bash
# 查看时间同步状态
docker exec tsa-chrony chronyc tracking

# 预期输出:
# Reference ID    : CA801234 (ntp.aliyun.com)
# Stratum         : 3
# System time     : 0.000123456 seconds fast of NTP time
# Last offset     : +0.000012345 seconds
# RMS offset      : 0.000023456 seconds
```

```bash
# 查看 NTP 源
docker exec tsa-chrony chronyc sources
```

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

### 12.1 启动 Demo 应用

```bash
# 1. 先编译安装 SDK
cd sdk
mvn clean install -DskipTests

# 2. 启动 Demo
cd ../sdk-demo
mvn spring-boot:run

# Demo 运行在 http://localhost:9090
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
curl -X POST http://localhost:9090/api/sm2/sign \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
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

#### SM2 加密/解密

```bash
# 加密
curl -X POST http://localhost:9090/api/sm2/encrypt \
  -H "Content-Type: application/json" \
  -d '{"text":"Secret message"}'

# 解密
curl -X POST http://localhost:9090/api/sm2/decrypt \
  -H "Content-Type: application/json" \
  -d '{
    "ciphertextBase64": "...",
    "privateKeyHex": "3e2a8b..."
  }'
```

#### TSA 时间戳请求

```bash
# 对文本打时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'

# 对文本先计算SM3再打时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/sm3 \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
```

**响应:**
```json
{
  "success": true,
  "status": 0,
  "serialNumber": "01",
  "genTime": "Mon Jul 21 10:30:00 CST 2026",
  "policyOid": "1.2.3.4.1",
  "hashAlgorithmOid": "1.2.156.10197.1.401",
  "messageImprintHex": "06c3a6f5e3a8e3a6...",
  "tokenBase64": "MIIF...",
  "responseBase64": "MIIF...",
  "tokenSize": 2048
}
```

---

## 13. 测试与验证

### 13.1 使用 curl 测试 TSA

```bash
# 1. 使用 GmSSL 生成时间戳请求
# (需要在安装了 GmSSL 的环境中执行)
gmssl ts -query -data "test.txt" -sm3 -no_nonce -out query.tsq

# 2. 发送请求到 TSA
curl -X POST http://localhost:8080/tsa \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @query.tsq \
  -o response.tsr

# 3. 查看响应
gmssl ts -reply -in response.tsr -text
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

### 13.3 使用 Java Demo 测试

```bash
# 1. 启动 TSA 服务
docker compose up --build -d

# 2. 等待服务就绪 (约30秒)
sleep 30

# 3. 编译并启动 Demo
cd sdk && mvn clean install -DskipTests
cd ../sdk-demo && mvn spring-boot:run

# 4. 测试 SM3
curl "http://localhost:9090/api/sm3/hash?text=Hello"

# 5. 测试时间戳
curl -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
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
docker stats tsa-server tsa-nginx tsa-chrony

# 监控 NTP 偏差
docker exec tsa-chrony chronyc tracking

# 监控 TSA 证书过期时间
docker exec tsa-server gmssl x509 -in /etc/tsa/certs/tsacert.pem -enddate
```

---

## 15. 故障排除

### 15.1 常见问题

#### Q: 构建失败 — GmSSL3 编译错误

```bash
# 检查 Docker 内存限制
docker info | grep "Total Memory"

# 建议: 至少分配 2GB 内存给 Docker
# Windows: Docker Desktop → Settings → Resources → Memory: 4GB
```

#### Q: nginx 返回 502 Bad Gateway

```bash
# 检查 fcgiwrap 是否在运行
docker exec tsa-server pgrep fcgiwrap

# 检查端口 9000
docker exec tsa-server ss -tlnp | grep 9000

# 查看 fcgiwrap 日志
docker logs tsa-server
```

#### Q: TSA 返回 500 — 时间戳生成失败

```bash
# 查看错误日志
docker exec tsa-server cat /var/log/tsa/tsa_error.log

# 检查证书是否存在
docker exec tsa-server ls -la /etc/tsa/certs/

# 检查序列号文件
docker exec tsa-server cat /etc/tsa/certs/tsaserial

# 手动测试 gmssl ts 命令
docker exec tsa-server gmssl ts -reply \
  -queryfile /tmp/test.tsq \
  -signer /etc/tsa/certs/tsacert.pem \
  -inkey /etc/tsa/certs/tsakey.pem \
  -md sm3 \
  -config /etc/tsa/openssl/tsa.cnf \
  -section tsa
```

#### Q: chrony 同步失败

```bash
# 检查 chrony 状态
docker exec tsa-chrony chronyc tracking

# 检查 NTP 源
docker exec tsa-chrony chronyc sources

# 如果 NTP 不可达，检查网络
docker exec tsa-chrony ping ntp.aliyun.com
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
# 确保使用正确的 CA 证书
docker exec tsa-server gmssl verify \
  -CAfile /etc/tsa/certs/cacert.pem \
  /etc/tsa/certs/tsacert.pem

# 检查时间戳令牌
gmssl ts -reply -in response.tsr -text
```

### 15.2 调试模式

```bash
# 以调试模式启动 (前台运行)
docker compose up --build

# 进入容器调试
docker exec -it tsa-server bash

# 手动执行 CGI 脚本
docker exec -it tsa-server \
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

### 16.4 数据卷说明（Docker）

| 卷 | 用途 |
|----|------|
| `tsa-certs` | SM2 CA/TSA 证书与私钥（tsa-server 写，nginx 只读挂载供下载） |
| `nginx-tls` | nginx HTTPS 传输层证书（与 SM2 签名证书分离） |
| `tsa-serial` | 预留序列号持久化 |
| `tsa-logs` / `nginx-logs` / `chrony-data` | 日志与 NTP 状态 |

### 16.5 一键命令速查

```powershell
# 启动
docker compose up --build -d

# 状态 / 日志
docker compose ps
docker compose logs -f tsa-server

# 证书
curl http://localhost:8080/tsa/cert -o tsacert.pem
curl http://localhost:8080/tsa/cacert -o cacert.pem

# SDK
mvn clean install -DskipTests
cd sdk-demo; mvn spring-boot:run

# 自测
pwsh ./scripts/test_tsa.ps1
```

---

---

## 17. GitHub Actions 镜像构建

### 17.1 工作流一览

| 文件 | 名称 | 说明 |
|------|------|------|
| `.github/workflows/docker-build.yml` | Docker Build & Push | 构建并推送三组件镜像到 GHCR |
| `.github/workflows/maven-ci.yml` | Maven CI | 编译 SDK / Demo，上传 jar |
| `.github/workflows/ci.yml` | CI | 路径变更检测与总览 |

### 17.2 Docker 构建行为

| 事件 | 是否构建 | 是否推送 GHCR |
|------|----------|---------------|
| `pull_request` | 是 | 否 |
| `push` 到 main/master/develop | 是（路径过滤） | 是 |
| `push` 标签 `v*` | 是 | 是 |
| `workflow_dispatch` | 是 | 可选（默认 true） |

**镜像名**（全小写）：

```text
ghcr.io/<owner>/<repo>/tsa-server
ghcr.io/<owner>/<repo>/nginx
ghcr.io/<owner>/<repo>/chrony
```

**常用标签**：`latest`、分支名、`sha-<short>`、语义化版本（由 `v1.2.3` 标签生成）。

**缓存**：使用 Buildx `type=gha` 缓存，加速 `tsa-server`（GmSSL3 编译）。  
**超时**：`tsa-server` 90 分钟，`nginx` 30 分钟，`chrony` 20 分钟。

### 17.3 手动触发

GitHub 仓库 → **Actions** → **Docker Build & Push** → **Run workflow**：

- `push_images`：是否推送
- `image_filter`：`all` / `tsa-server` / `nginx` / `chrony`

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

**文档结束** · 版本 1.0.1 · 工程路径以本仓库根目录为准。
| SM4 | 1.2.156.10197.1.104 | 分组密码算法 |

### 16.2 RFC 3161 相关 OID

| 名称 | OID | 说明 |
|------|-----|------|
| id-ct-TSTInfo | 1.2.840.113549.1.9.16.1.4 | TSTInfo 内容类型 |
| id-aa-timeStampToken | 1.2.840.113549.1.9.16.2.14 | 时间戳令牌属性 |
| timeStamping | 1.3.6.1.5.5.7.3.8 | 时间戳 EKU |

### 16.3 TSA PKIStatus 状态码

| 状态码 | 名称 | 说明 |
|--------|------|------|
| 0 | granted | 请求已接受 |
| 1 | grantedWithMods | 请求已接受(有修改) |
| 2 | rejection | 请求被拒绝 |
| 3 | waiting | 请求处理中 |
| 4 | revocationWarning | 证书即将吊销 |
| 5 | revocationNotification | 证书已吊销 |

### 16.4 MIME 类型

| 类型 | 说明 |
|------|------|
| application/timestamp-query | TimeStampReq 请求 MIME 类型 |
| application/timestamp-reply | TimeStampResp 响应 MIME 类型 |

### 16.5 参考标准

- **RFC 3161**: Internet X.509 Public Key Infrastructure Time-Stamp Protocol (TSP)
- **GM/T 0003**: SM2 椭圆曲线公钥密码算法
- **GM/T 0004**: SM3 密码杂凑算法
- **GM/T 0010**: SM2 密码算法使用规范

---

> **文档结束** | 如有疑问请检查故障排除章节或查看容器日志
