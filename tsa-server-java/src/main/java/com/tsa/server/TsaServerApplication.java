package com.tsa.server;

import com.shineyue.tsa.aot.TsaRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * TSA Java Server - 高性能 RFC 3161 时间戳服务
 *
 * 替代 CGI 方案，使用 BouncyCastle SM3withSM2 直接签名：
 *   - 无 fork 开销
 *   - 无 shell 解析
 *   - 纯 Java 进程内签名 (~30ms/req)
 */
@SpringBootApplication
@ImportRuntimeHints(TsaRuntimeHints.class)
public class TsaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TsaServerApplication.class, args);
    }
}
