package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code docs/PLAN-v2.md} §5.1: through real Spring MVC dispatching, a statement run by the handler records the
 * {@code HANDLER} phase, and one run while Jackson serializes the returned body, as lazy loading does, records
 * {@code RESPONSE}.
 */
class RequestPhaseMvcTests {

    private final RequestPhases phases = new RequestPhases();
    private final SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 10, 100L, 2_048, 256, 5);

    @Test
    void sqlRecordsTheRequestPhaseItRanIn() throws Exception {
        sql.setRequestPhases(phases);
        MockMvc mvc = standaloneSetup(new OrdersController(sql))
                .addFilters(new RequestCorrelationFilter(
                        new RequestCorrelationRegistry(10),
                        new HttpExchangeTraceRegistry(10),
                        "/bootui",
                        null,
                        1_000L,
                        phases))
                .addInterceptors(new RequestPhaseInterceptor(phases))
                .setControllerAdvice(new RequestPhaseResponseBodyAdvice(phases))
                .build();

        mvc.perform(get("/orders")).andExpect(status().isOk());

        assertThat(sql.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::sql, SqlTraceRecorder.CapturedStatement::requestPhase)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("select * from orders", "HANDLER"),
                        org.assertj.core.groups.Tuple.tuple("select * from order_lines", "RESPONSE"));
        String requestId = sql.recent().get(0).requestId();
        RequestPhases.Markers markers = phases.markers(requestId);
        assertThat(markers.filtersAt()).isNotNull();
        assertThat(markers.handlerAt()).isGreaterThanOrEqualTo(markers.filtersAt());
        assertThat(markers.responseAt()).isGreaterThanOrEqualTo(markers.handlerAt());
    }

    @Test
    void theErrorDispatchRunsUnderTheRequestsOwnId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui", null, 1_000L, phases);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        List<String> seen = new java.util.ArrayList<>();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seen.add(BootUiCorrelation.current().requestId()));

        String requestId = ((io.github.jdubois.bootui.spi.CorrelationContext)
                        request.getAttribute(RequestCorrelationFilter.CORRELATION_ATTRIBUTE))
                .requestId();
        assertThat(seen).containsExactly(requestId);
        assertThat(BootUiCorrelation.current().isEmpty()).isTrue();
    }

    @RestController
    static class OrdersController {

        private final SqlTraceRecorder sql;

        OrdersController(SqlTraceRecorder sql) {
            this.sql = sql;
        }

        @GetMapping("/orders")
        Order orders() {
            run(sql, "select * from orders");
            return new Order(sql);
        }
    }

    /** A body whose getter queries while it is serialized, as a lazily loaded JPA association does. */
    public static final class Order {

        private final SqlTraceRecorder sql;

        Order(SqlTraceRecorder sql) {
            this.sql = sql;
        }

        public List<String> getLines() {
            run(sql, "select * from order_lines");
            return List.of("line-1");
        }
    }

    private static void run(SqlTraceRecorder sql, String statement) {
        sql.record(
                SqlTraceRecorder.StatementType.STATEMENT,
                SqlTraceRecorder.Category.SELECT,
                statement,
                List.of(),
                5L,
                true,
                null,
                null,
                0,
                "c1",
                Thread.currentThread().getName());
    }
}
