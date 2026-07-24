package com.tsa.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TSA SDK Demo 应用
 *
 * 演示如何使用 tsa-spring-boot-starter SDK
 * 提供 REST API 测试时间戳请求、SM2 签名验证、SM3 摘要计算
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
