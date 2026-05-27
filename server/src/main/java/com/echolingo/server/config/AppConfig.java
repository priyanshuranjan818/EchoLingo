package com.echolingo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "echolingo")
public record AppConfig(
        String cacheDir,
        String ytdlpPath,
        long ytdlpTimeoutMs,
        String ytdlpProxyList,
        String deeplKey,
        String groqApiKey
) {
}
