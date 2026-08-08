package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.TransactionReport;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Transactions panel ({@code GET /bootui/api/transactions}).
 *
 * <p>Honestly unavailable on Quarkus: the Spring adapter captures transaction boundaries through Spring
 * Framework's {@code TransactionExecutionListener} SPI, registered against {@code
 * ConfigurableTransactionManager} beans (see {@code BootUiTransactionManagerBeanPostProcessor}). Quarkus'
 * transaction management goes through Narayana's JTA {@code TransactionManager}/{@code Synchronization} or
 * the CDI {@code @Transactional} interceptor, neither of which exposes a comparable per-boundary listener
 * hook without either wrapping every {@code @Transactional} bean with a custom CDI interceptor (a much more
 * invasive instrumentation than the Spring adapter's opt-in listener registration) or depending on
 * Narayana-internal APIs. Rather than force false parity — a Quarkus capture path that behaves differently
 * from Spring's (e.g. missing isolation, or requiring an interceptor ahead of every other
 * {@code @Transactional} interceptor to see accurate nesting) — this resource follows the {@link
 * SecurityLogsResource} precedent: it always reports {@code available=false} with a clear reason, so the
 * panel renders an honest "not supported yet" state instead of silently showing an empty table.</p>
 *
 * <p>Read-only: no clear/recording actions are exposed, since there is nothing to pause, resume, or clear.
 * See {@code docs/QUARKUS-SUPPORT.md} for the documented limitation.</p>
 */
@Path("/bootui/api/transactions")
public class TransactionsResource {

    static final String UNAVAILABLE_REASON = "Transaction boundary capture requires Spring Framework's"
            + " TransactionExecutionListener hook, which has no Quarkus/Narayana equivalent yet. The"
            + " Transactions panel is not available on this stack.";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransactionReport transactions() {
        return TransactionReport.unavailable(UNAVAILABLE_REASON);
    }
}
