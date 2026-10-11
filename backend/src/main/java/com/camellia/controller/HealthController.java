package com.camellia.controller;

import com.camellia.integration.snowflake.DatabaseMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness for load balancers, Lambda Web Adapter readiness checks and uptime monitors. Deliberately touches neither
 * the database nor Gemini: a check that queried Snowflake every minute would keep a warehouse from ever suspending.
 */
@RestController
public class HealthController {

    private final DatabaseMode mode;

    public HealthController(DatabaseMode mode) {
        this.mode = mode;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "databaseMode", mode.name());
    }
}
