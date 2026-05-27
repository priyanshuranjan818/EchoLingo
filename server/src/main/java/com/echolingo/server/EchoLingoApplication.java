package com.echolingo.server;

import com.echolingo.server.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class EchoLingoApplication {
    public static void main(String[] args) {
        SpringApplication.run(EchoLingoApplication.class, args);
    }
}
