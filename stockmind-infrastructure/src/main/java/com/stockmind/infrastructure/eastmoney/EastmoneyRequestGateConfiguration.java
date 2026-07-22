package com.stockmind.infrastructure.eastmoney;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EastmoneyRequestGateConfiguration {
    @Bean
    EastmoneyRequestGate eastmoneyRequestGate(
            @Value("${stockmind.eastmoney.gate.minimum-interval-millis:1200}") long minimumIntervalMillis,
            @Value("${stockmind.eastmoney.gate.cache-ttl-millis:30000}") long cacheTtlMillis,
            @Value("${stockmind.eastmoney.gate.forbidden-circuit-millis:300000}") long forbiddenCircuitMillis,
            @Value("${stockmind.eastmoney.gate.error-backoff-millis:5000}") long errorBackoffMillis) {
        return new EastmoneyRequestGate(minimumIntervalMillis, cacheTtlMillis,
                forbiddenCircuitMillis, errorBackoffMillis);
    }
}
