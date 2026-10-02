package ua.edu.highload.common;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness requires a usable shared database, not just a live HTTP listener. */
@RestController
public class HealthController {
    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
