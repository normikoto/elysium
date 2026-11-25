package dev.biribiri.elysium;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Keeps the health check on the main port once {@code management.server.port} moves the actuator to a port of its own,
 * so that checks written for the main port, such as Docker's or a load balancer's, keep working. Spring Boot's
 * {@code additional-path} for health groups can't do this, because it only takes a single segment such as
 * {@code /healthz}. While the actuator shares the main port, it serves these paths itself, and this isn't created.
 *
 * <p>It only gives the status, as the actuator does by default, since the main port is public. It's left out of the API
 * documentation, like the actuator it stands in for.
 */
@Hidden
@RestController
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
class MainPortHealth {

    private final HealthEndpoint health;
    private final HttpCodeStatusMapper statusCodes;

    MainPortHealth(HealthEndpoint health, ObjectProvider<HttpCodeStatusMapper> statusCodes) {
        this.health = health;
        this.statusCodes = statusCodes.getIfAvailable(HttpCodeStatusMapper::getDefault);
    }

    @GetMapping("/actuator/health")
    ResponseEntity<Map<String, String>> health() {
        return respond(health.health());
    }

    @GetMapping("/actuator/health/liveness")
    ResponseEntity<Map<String, String>> liveness() {
        return respond(health.healthForPath("liveness"));
    }

    @GetMapping("/actuator/health/readiness")
    ResponseEntity<Map<String, String>> readiness() {
        return respond(health.healthForPath("readiness"));
    }

    private ResponseEntity<Map<String, String>> respond(HealthDescriptor descriptor) {
        if (descriptor == null) {
            return ResponseEntity.notFound().build();
        }
        var status = descriptor.getStatus();
        return ResponseEntity.status(statusCodes.getStatusCode(status)).body(Map.of("status", status.getCode()));
    }
}
