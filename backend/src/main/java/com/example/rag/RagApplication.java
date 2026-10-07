package com.example.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagApplication {

    /** 启动 RAG 应用并加载 Spring 配置。 */
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
