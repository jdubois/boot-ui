package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.Serializable;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link DataController}.
 *
 * <p>Covers the Spring Data repository list and detail endpoints, including the
 * empty-context cases (no bean factory, no repository beans) and the three
 * supported detail lookup keys (bean name, fully qualified interface name,
 * simple interface name).</p>
 */
class DataControllerTests {

    @Test
    void repositoriesReturnsEmptyReportWhenNoBeanFactory() throws Exception {
        ObjectProvider<ListableBeanFactory> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new DataController(provider)).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springDataPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.repositories").isArray())
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void repositoriesReturnsEmptyReportWhenNoRepositoryBeans() throws Exception {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(RepositoryFactoryInformation.class))
                .thenReturn(new String[0]);

        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springDataPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void repositoriesReturnsSummaryForDiscoveredRepository() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);

        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.repositories[0].beanName").value("widgetRepository"))
                .andExpect(jsonPath("$.repositories[0].repositoryInterface")
                        .value(WidgetRepository.class.getName()))
                .andExpect(jsonPath("$.repositories[0].domainType").value(Widget.class.getName()))
                .andExpect(jsonPath("$.repositories[0].idType").value(Long.class.getName()))
                // WidgetRepository lives outside the spring-data package tree, so it
                // resolves to the generic store module marker.
                .andExpect(jsonPath("$.repositories[0].storeModule").value("GENERIC"))
                .andExpect(jsonPath("$.repositories[0].queryMethodCount").value(1))
                .andExpect(jsonPath("$.repositories[0].fragmentCount").value(0));
    }

    @Test
    void repositoryDetailLooksUpByBeanName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories/widgetRepository"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beanName").value("widgetRepository"))
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()))
                .andExpect(jsonPath("$.methods").isArray())
                .andExpect(jsonPath("$.methods[?(@.name=='findByName')]").exists())
                .andExpect(jsonPath("$.methods[?(@.name=='findByName')].origin").value("QUERY"));
    }

    @Test
    void repositoryDetailLooksUpByFullyQualifiedInterfaceName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories/" + WidgetRepository.class.getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()));
    }

    @Test
    void repositoryDetailLooksUpBySimpleInterfaceName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories/WidgetRepository"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()));
    }

    @Test
    void repositoryDetailReturnsNotFoundForUnknownRepository() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(new DataController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/data/repositories/UnknownRepository"))
                .andExpect(status().isNotFound());
    }

    private static ListableBeanFactory beanFactoryWithRepository(String beanName, Class<?> repositoryInterface) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        RepositoryFactoryInformation<?, ?> info = mock(RepositoryFactoryInformation.class);
        RepositoryInformation information = mock(RepositoryInformation.class);

        when(factory.getBeanNamesForType(RepositoryFactoryInformation.class))
                .thenReturn(new String[] { beanName });
        when(factory.getBean(eq(beanName), eq(RepositoryFactoryInformation.class)))
                .thenAnswer(invocation -> info);
        when(info.getRepositoryInformation()).thenReturn(information);

        // Mockito cannot return Class<?> from raw-typed getters without an unchecked
        // cast, so use doReturn-style stubbing via thenAnswer.
        when(information.getRepositoryInterface()).thenAnswer(invocation -> repositoryInterface);
        when(information.getDomainType()).thenAnswer(invocation -> Widget.class);
        when(information.getIdType()).thenAnswer(invocation -> Long.class);
        when(information.getRepositoryBaseClass()).thenAnswer(invocation -> null);

        // Classify methods: findByName is a derived query, everything else (Object methods
        // are filtered upstream) is treated as a base CRUD method.
        when(information.isCustomMethod(any(Method.class))).thenReturn(false);
        when(information.isBaseClassMethod(any(Method.class))).thenAnswer(invocation -> {
            Method m = invocation.getArgument(0);
            return !"findByName".equals(m.getName());
        });
        when(information.isQueryMethod(any(Method.class))).thenAnswer(invocation -> {
            Method m = invocation.getArgument(0);
            return "findByName".equals(m.getName());
        });

        return factory;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /** Test-only domain class for the synthetic repository fixture. */
    public static class Widget implements Serializable {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** Test-only repository fixture used to exercise method classification. */
    public interface WidgetRepository extends Repository<Widget, Long> {
        Widget findByName(String name);
    }
}
