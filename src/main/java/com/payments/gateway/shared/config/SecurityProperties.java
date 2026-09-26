package com.payments.gateway.shared.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.security")
public record SecurityProperties(List<String> adminTokens, String dataEncryptionKey) {

    public SecurityProperties {
        adminTokens = adminTokens == null ? List.of() : adminTokens.stream().filter(t -> t != null && !t.isBlank()).toList();
    }
}
