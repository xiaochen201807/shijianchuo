package com.tsa.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * TSA Spring Boot Starter 自动配置类
 *
 * 当项目中引入 tsa-spring-boot-starter 依赖后，
 * 会自动创建 TsaClient Bean 和相关组件。
 *
 * 可通过 application.yml 配置:
 *
 * tsa:
 *   url: http://localhost:8080/tsa
 *   connect-timeout: 5000
 *   read-timeout: 30000
 *   policy-oid: 1.2.3.4.1
 *   cert-req: true
 *   hash-algorithm: SM3
 *   auto-register-provider: true
 *
 * 也可以通过设置 tsa.enabled=false 来禁用自动配置
 */
@Configuration
@EnableConfigurationProperties(TsaProperties.class)
@ConditionalOnClass(TsaClient.class)
@ConditionalOnProperty(prefix = "tsa", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TsaAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TsaAutoConfiguration.class);

    /**
     * 创建 TsaClient Bean
     *
     * 当容器中不存在 TsaClient 时自动创建
     */
    @Bean
    @ConditionalOnMissingBean(TsaClient.class)
    public TsaClient tsaClient(TsaProperties properties) {
        // 注册 BouncyCastle Provider
        if (properties.isAutoRegisterProvider()) {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                logger.info("BouncyCastle Provider registered");
            }
        }

        logger.info("Creating TsaClient with properties: {}", properties);

        // 验证必要配置
        if (properties.getUrl() == null || properties.getUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("TSA url must be configured (tsa.url)");
        }

        return new TsaClient(properties);
    }
}
