package example.bootuiexplorer;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Application-package fixtures, intentionally outside BootUI's excluded implementation packages. */
public final class InvocationFixtures {

    private InvocationFixtures() {}

    public interface OrdersRepository extends Repository<String, Long> {
        String lookup(Long id);
    }

    public static class OrdersRepositoryTarget implements OrdersRepository {
        private final JdbcTemplate jdbc;
        public int calls;

        public OrdersRepositoryTarget(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String lookup(Long id) {
            calls++;
            return jdbc.queryForObject("select 'order'", String.class);
        }
    }

    public interface OrderService {
        String load(Long id);

        String fail();
    }

    /** A repository whose own proxy is wrapped again by the application's caching advisor. */
    public interface AuditRepository extends Repository<String, Long> {
        @Cacheable("orders")
        String record(Long id);
    }

    public static class AuditRepositoryTarget implements AuditRepository {
        @Override
        public String record(Long id) {
            return "audited";
        }
    }

    @Service
    public static class DefaultOrderService implements OrderService {
        private final OrdersRepository repository;
        private final JdbcTemplate jdbc;
        public final IllegalArgumentException failure = new IllegalArgumentException("private payload");

        public DefaultOrderService(OrdersRepository repository, JdbcTemplate jdbc) {
            this.repository = repository;
            this.jdbc = jdbc;
        }

        @Override
        @Transactional
        @Cacheable("orders")
        public String load(Long id) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    jdbc.queryForObject("select 2", Integer.class);
                }
            });
            return repository.lookup(id);
        }

        @Override
        public String fail() {
            throw failure;
        }
    }

    @RestController
    public static class OrdersController {
        private final OrderService service;

        public OrdersController(OrderService service) {
            this.service = service;
        }

        @GetMapping("/orders")
        public String get() {
            return service.load(1L);
        }

        public String handledFailure() {
            try {
                service.fail();
                return "unexpected";
            } catch (IllegalArgumentException expected) {
                return "handled";
            }
        }
    }

    public static class PlainService {
        public String call() {
            return "value";
        }
    }

    public interface Inventory {
        String count();
    }

    public static class FactoryOnlyService implements Inventory {
        private final String value;

        private FactoryOnlyService(String value) {
            this.value = value;
        }

        public static FactoryOnlyService create() {
            return new FactoryOnlyService("initialized");
        }

        @Override
        public String count() {
            return value;
        }
    }

    public static class ProtectedConstructorService extends PlainService {
        protected ProtectedConstructorService() {}

        public static ProtectedConstructorService create() {
            return new ProtectedConstructorService();
        }
    }

    public static class PackageConstructorService extends PlainService {
        PackageConstructorService() {}

        public static PackageConstructorService create() {
            return new PackageConstructorService();
        }
    }

    /** A plain application service whose concrete type is still injected and looked up directly. */
    public static class DefaultInventory implements Inventory {
        @Override
        public String count() {
            return "1";
        }
    }

    public static class InventoryHolder {
        private final DefaultInventory inventory;

        public InventoryHolder(DefaultInventory inventory) {
            this.inventory = inventory;
        }

        public String count() {
            return inventory.count();
        }
    }

    public static class FinalAccessorService {
        private final String value;

        public FinalAccessorService(String value) {
            this.value = value;
        }

        public final String value() {
            return value;
        }

        public String call() {
            return value;
        }
    }
}
