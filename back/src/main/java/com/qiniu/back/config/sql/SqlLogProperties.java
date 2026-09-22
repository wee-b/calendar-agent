package com.qiniu.back.config.sql;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.sql-log")
public class SqlLogProperties {

    private boolean enabled;

    @PostConstruct
    void configureP6SpyLogger() {
        ConditionalSqlLogger.setEnabled(enabled);
    }
}
