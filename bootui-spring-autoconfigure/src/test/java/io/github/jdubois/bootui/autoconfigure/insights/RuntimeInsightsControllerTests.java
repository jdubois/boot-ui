package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.EventLoopBlocking;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;

class RuntimeInsightsControllerTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void servesTheReportAndAnUnknownObservationAsUnavailableWithoutRecordingAnything() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(JournalAggregates.class, JournalAggregates::new);
            context.registerBean(BootUiProperties.class, BootUiProperties::new);
            context.registerBean(RuntimeInsightsController.class);
            context.refresh();
            MockMvc mvc = standaloneSetup(context.getBean(RuntimeInsightsController.class))
                    .build();

            mvc.perform(get("/bootui/api/runtime-insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.window.requests").value(0))
                    .andExpect(jsonPath("$.checks.length()").value(21));
            mvc.perform(get("/bootui/api/runtime-insights/insights/repeated-selects:0000000000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
            mvc.perform(get("/bootui/api/runtime-insights/impact").param("symbol", "orderService"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.reason")
                            .value(org.hamcrest.Matchers.containsString("beans could not be read")));
            mvc.perform(get("/bootui/api/runtime-insights/impact/symbols").param("query", "orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.query").value("orders"))
                    .andExpect(jsonPath("$.symbols.length()").value(0))
                    .andExpect(jsonPath("$.total").value(0));
            assertThat(journal.status().lastSequence())
                    .as("reading the panel records nothing")
                    .isLessThanOrEqualTo(0);
        }
    }

    @Test
    void listsTheDeclaredRoutesNoRequestOfThisRunReached() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(JournalAggregates.class, JournalAggregates::new);
            context.registerBean(BootUiProperties.class, BootUiProperties::new);
            context.registerBean(MappingProvider.class, () -> new MappingProvider() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public List<MappingDto> mappings() {
                    return List.of(new MappingDto("GET", "/api/orders", "com.example.Orders#list()", null, null));
                }
            });
            context.registerBean(RuntimeInsightsController.class);
            context.refresh();
            MockMvc mvc = standaloneSetup(context.getBean(RuntimeInsightsController.class))
                    .build();

            mvc.perform(get("/bootui/api/runtime-insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notExercised[0]").value("GET /api/orders"))
                    .andExpect(jsonPath("$.notExercisedOmitted").value(0));
        }
    }

    @Test
    void theStackDecidesWhereAnObservationApplies() {
        try (GenericApplicationContext servlet = new GenericApplicationContext();
                GenericReactiveWebApplicationContext reactive = new GenericReactiveWebApplicationContext()) {
            servlet.registerBean(RuntimeJournal.class, () -> journal);
            reactive.registerBean(RuntimeJournal.class, () -> journal);
            servlet.refresh();
            reactive.refresh();

            assertThat(eventLoopCheck(servlet).status()).isEqualTo("NOT_APPLICABLE");
            assertThat(eventLoopCheck(reactive).status()).isEqualTo("EVALUATED");
        }
    }

    @Test
    void poolSizesAreReadFromAHikariDataSourceThroughSqlTraceItsProxyAndUnknownOtherwise() {
        try (HikariDataSource hikari = new HikariDataSource();
                GenericApplicationContext context = new GenericApplicationContext()) {
            hikari.setMaximumPoolSize(7);
            DataSource traced = SqlTracingProxies.wrapNamed(
                    hikari, new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5), "dataSource");
            context.registerBean("dataSource", DataSource.class, () -> traced);
            context.registerBean("plain", DataSource.class, SimpleDriverDataSource::new);
            context.registerBean("notADataSource", String.class, () -> "x");
            context.refresh();
            DataSourcePoolSizes sizes = new DataSourcePoolSizes(context);

            assertThat(sizes.apply("dataSource")).isEqualTo(7);
            assertThat(sizes.apply("plain")).isNull();
            assertThat(sizes.apply("notADataSource")).isNull();
            assertThat(sizes.apply("missing")).isNull();
            assertThat(sizes.apply(null)).isNull();
        }
    }

    private RuntimeInsightCheckDto eventLoopCheck(GenericApplicationContext context) {
        return new RuntimeInsightsController(
                        context,
                        new BootUiProperties(),
                        context.getBeanProvider(RuntimeJournal.class),
                        context.getBeanProvider(JournalAggregates.class),
                        context.getBeanProvider(MappingProvider.class),
                        context.getBeanProvider(BeanProvider.class))
                .report().checks().stream()
                        .filter(check -> check.kind().equals(EventLoopBlocking.KIND))
                        .findFirst()
                        .orElseThrow();
    }
}
