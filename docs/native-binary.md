# Java 程序：原生二进制构建说明（无 JVM）

## 结论先看

| 模块 | 产物形态 | 是否需要 JVM | 说明 |
|------|----------|--------------|------|
| **sdk** (`tsa-spring-boot-starter`) | **JAR 库** | 使用方若是 Java 项目则需要 | Maven 依赖库，不能改成“一个二进制”给别人 import |
| **sdk-demo** (`tsa-demo`) | **原生可执行文件** | **不需要** | GraalVM Native Image，可直接跑 / 打进无 JRE 镜像 |

**TSA 服务端 All-in-One 镜像本身就不含 JVM**（Tongsuo + nginx + fcgiwrap + chrony）。  
本说明只针对 **Java Demo / 客户端程序** 的发布形态。

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

### 1.3 CI 产物

GitHub Actions：**Native Binary CI**（`.github/workflows/native-ci.yml`）

- Artifact：`tsa-demo-linux-amd64` / `tsa-demo-linux-arm64`
- 镜像：`ghcr.io/xiaochen201807/shijianchuo/tsa-demo:native`（multi-arch，无 JVM）

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

Docker：

```bash
docker run --rm -p 9090:9090 \
  -e TSA_URL=http://host.docker.internal:8080/tsa \
  ghcr.io/xiaochen201807/shijianchuo/tsa-demo:native
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

## 4. 与 TSA 服务镜像的关系

```text
┌─────────────────────────────┐     POST /tsa      ┌──────────────────────────┐
│ tsa-demo (native binary)    │ ─────────────────► │ tsa All-in-One 镜像      │
│ 无 JVM                      │                    │ Tongsuo+nginx+fcgiwrap   │
│ 镜像: .../tsa-demo:native   │                    │ 无 JVM                   │
└─────────────────────────────┘                    └──────────────────────────┘
```

两套镜像都 **不包含 JVM**。

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
