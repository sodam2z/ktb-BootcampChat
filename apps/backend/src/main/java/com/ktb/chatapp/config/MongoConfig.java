package com.ktb.chatapp.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer(
            @Value("${app.mongodb.pool.max-size}") int maxPoolSize,
            @Value("${app.mongodb.pool.min-size}") int minPoolSize,
            @Value("${app.mongodb.pool.max-wait-time-ms}") long maxWaitTimeMs) {
        return builder -> builder.applyToConnectionPoolSettings(pool -> pool
                .maxSize(maxPoolSize)
                .minSize(minPoolSize)
                .maxWaitTime(maxWaitTimeMs, TimeUnit.MILLISECONDS));
    }
}
