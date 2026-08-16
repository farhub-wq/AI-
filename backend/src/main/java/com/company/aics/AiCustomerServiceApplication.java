package com.company.aics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot 启动入口：AI 客服后端应用。
 * 启用数据源与 JPA，业务数据持久化到 MySQL（见 {@code AppDataStore}）；并通过 {@link ConfigurationPropertiesScan} 扫描配置属性类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCustomerServiceApplication {

    /**
     * 启动 Spring 应用上下文。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiCustomerServiceApplication.class, args);
    }
}
