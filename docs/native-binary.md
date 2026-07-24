# Java 程序：原生二进制构建说明（无 JVM）

## 结论先看

| 模块 | 产物形态 | 是否需要 JVM | 说明 |
|------|----------|--------------|------|
| **最终镜像** `.../tsa` | Docker 镜像 | **不需要** | **生产唯一入口**：内嵌 `tsa-demo` 原生二进制 + TSA 服务 |
| **sdk** | JAR 库 | 作为依赖时 | 给其它 Java 项目 import |
| **sdk-demo 源码** | 可编成原生文件 | 编完不需要 | 由 Dockerfile 多阶段编进最终镜像 |

```text
Dockerfile 多阶段:
  [1] GraalVM  →  /usr/local/bin/tsa-demo  (原生)
  [2] Ubuntu   →  Tongsuo + nginx + fcgiwrap + chrony
                  + COPY tsa-demo
                  + supervisor 一起启动
```

---

## 1. 构建原生二进制

### 1.1 本机（需 GraalVM 21 + `native-image`）

```bash
# Linux / macOS
bash ./scripts/build-native.sh
# 产物: sdk-demo/target/tsa-demo

# Windows (需 MSVC 工具链)
pwsh ./scripts/build-native.ps1
# 产物: sdk-demo/target/tsa-demo.exe
```

等价 Maven：

```bash
mvn -B -f pom.xml -pl sdk -am clean install -DskipTests
mvn -B -f pom.xml -pl sdk-demo -am -Pnative -DskipTests package
```

### 1.2 不装 GraalVM：用 Docker 构建（推荐）

```bash
docker compose -f docker-compose.demo.yml up --build -d
# 镜像内只有原生二进制，无 JRE
curl "http://localhost:9090/api/sm3/hash?text=Hello"
```

Dockerfile：[`Dockerfile.demo`](../Dockerfile.demo)  
- 构建阶段：GraalVM Native Image  
- 运行阶段：`debian:bookworm-slim` + 单个 `/app/tsa-demo`，**无 JVM**

### 1.3 CI / 发布

**不再单独维护 Native 工作流**（与最终镜像 Dockerfile 阶段 1 重复，浪费分钟数）。

| 方式 | 说明 |
|------|------|
| **生产发布** | `Docker Build & Push` 多阶段构建，二进制编进 `ghcr.io/.../tsa` |
| **本机调试二进制** | `scripts/build-native.sh` / `.ps1` |
| **库编译检查** | `Maven CI`（只出 jar，不跑 native-image） |

---


## 2. 运行原生 Demo

```bash
# 指向 TSA 服务
export TSA_URL=http://127.0.0.1:8080/tsa
./sdk-demo/target/tsa-demo

# 或
TSA_URL=http://192.168.1.10:8080/tsa ./tsa-demo-linux-amd64
```

```bash
curl -s "http://localhost:9090/api/sm3/hash?text=Hello"
curl -s -X POST http://localhost:9090/api/tsa/timestamp/text \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, TSA!"}'
```

生产请直接跑最终镜像（已内嵌该二进制）：

```bash
docker pull ghcr.io/xiaochen201807/shijianchuo/tsa:latest
docker compose -f docker-compose.ghcr.yml up -d
curl "http://localhost:8080/api/sm3/hash?text=Hello"
```

---


## 3. 为何 SDK 仍是 JAR？

`tsa-spring-boot-starter` 是给 **其它 Java/Spring Boot 工程** 当依赖用的：

```xml
<dependency>
  <groupId>com.tsa</groupId>
  <artifactId>tsa-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

库必须以 class/jar 形式被编译进对方工程；对方若也做 Native Image，会把本库 **静态链进对方的二进制**，运行时仍然 **不需要单独装 JVM**（对方的原生程序里没有 JRE）。

若你的目标是「服务器上只跑一个可执行文件」，请用 **`tsa-demo` 原生二进制**，不要在生产镜像里塞 `java -jar`。

---

## 4. 与最终镜像的关系

```text
最终镜像 ghcr.io/.../tsa
  supervisor
    ├─ Tongsuo/nginx/fcgiwrap/chrony   →  POST /tsa
    └─ /usr/local/bin/tsa-demo (native) →  /api/*   (无 JVM)
```

一条流水线、一个镜像，不再单独发布 `tsa-demo` 镜像。

---


## 5. 体积与注意点

- 首次 `native-image` 编译较慢（数分钟～十余分钟），需要较大内存（建议 ≥ 4GB）。
- BouncyCastle 已通过 `TsaRuntimeHints` 注册反射/资源提示；若 native 运行时报缺类，再补 hints。
- 原生二进制与 OS/架构绑定：linux-amd64、linux-arm64、windows-amd64 不可混用。

---

## 6. 常用命令速查

```bash
# 仅校验 Java 编译（出 jar，CI 库发布）
mvn -f pom.xml clean install -DskipTests

# 出无 JVM 可执行文件
mvn -f pom.xml -pl sdk-demo -am -Pnative -DskipTests package

# 无 JVM Demo 镜像
docker build -f Dockerfile.demo -t tsa-demo:native .
docker run --rm -p 9090:9090 -e TSA_URL=http://host.docker.internal:8080/tsa tsa-demo:native
```
