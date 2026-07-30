package com.tsa.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * TSA SDK Demo 应用
 *
 * 演示 tsa-spring-boot-starter：REST API（SM2/SM3/TSA）
 *
 * 构建:
 *   - jar (需 JVM):  mvn -pl sdk-demo -am package
 *   - 原生二进制:    mvn -pl sdk-demo -am -Pnative -DskipTests package
 *                    产物 sdk-demo/target/tsa-demo  (无 JVM)
 */
@SpringBootApplication(scanBasePackages = {"com.tsa.demo", "com.shineyue.tsa"})
@ConfigurationPropertiesScan
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

