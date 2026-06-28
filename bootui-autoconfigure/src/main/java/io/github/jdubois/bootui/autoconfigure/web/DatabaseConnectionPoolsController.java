package io.github.jdubois.bootui.autoconfigure.web;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only database connection-pool state for supported JDBC pool beans
 * declared in the current application context.
 *
 * <p>This controller is strictly read-only: it only reads pool configuration
 * getters and the live counters published by {@link HikariPoolMXBean}. It never
 * borrows a connection, executes SQL, or mutates the pool (no resize, suspend,
 * or evict). Credentials embedded in the JDBC URL and the pool username are
 * routed through {@link SecretMasker} before they reach the browser.</p>
 *
 * <p>The behaviour lives in the framework-neutral engine {@link ConnectionPoolService}; this controller is a
 * thin binding. Pool discovery is the Spring-specific {@code SpringConnectionPoolProvider} (HikariCP beans +
 * MXBean), wired in the nested {@code @ConditionalOnClass(HikariDataSource.class)} backend configuration.</p>
 */
@RestController
@ConditionalOnClass(HikariDataSource.class)
@RequestMapping("/bootui/api/database-connection-pools")
public class DatabaseConnectionPoolsController {

    private final ConnectionPoolService service;

    public DatabaseConnectionPoolsController(ConnectionPoolService service) {
        this.service = service;
    }

    @GetMapping("/pools")
    public HikariPoolsReport pools() {
        return service.report();
    }

    @GetMapping("/pools/{name}/snapshot")
    public ResponseEntity<HikariPoolSnapshotDto> snapshot(@PathVariable String name) {
        HikariPoolSnapshotDto snapshot = service.snapshot(name);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }
}
