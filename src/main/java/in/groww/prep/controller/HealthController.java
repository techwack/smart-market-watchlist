package in.groww.prep.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Cheap sanity endpoint — separate from Actuator's /actuator/health so judges
 * (and you, mid-demo) get a fast, human-readable "is this thing alive" check.
 */
@RestController
public class HealthController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "service", "code-by-groww-prep",
                "timestamp", Instant.now().toString()
        );
    }
}
