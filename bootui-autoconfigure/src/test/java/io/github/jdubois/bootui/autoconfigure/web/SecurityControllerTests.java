package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Standalone MockMvc tests for {@link SecurityController}.
 *
 * <p>Exercises:</p>
 * <ul>
 *   <li>Multiple {@code SecurityFilterChain} beans are reflected as one DTO entry each, in order.</li>
 *   <li>A missing {@code FilterChainProxy} (Spring Security absent / not configured) produces a
 *       stable {@code springSecurityPresent=false} report, not a failure.</li>
 *   <li>Auth block includes {@code configuredUsername} from environment but never any password.</li>
 *   <li>No raw credentials appear in the response when {@code UserDetailsService} beans are present.</li>
 * </ul>
 */
class SecurityControllerTests {

    // ── chain listing ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static MockMvc buildMvc(
            FilterChainProxy proxy,
            AuthenticationProvider authProvider,
            UserDetailsService userDetailsService,
            MockEnvironment env,
            BootUiProperties properties) {
        ObjectProvider<FilterChainProxy> proxyProvider = mock(ObjectProvider.class);
        when(proxyProvider.getIfAvailable()).thenReturn(proxy);
        when(proxyProvider.stream()).thenReturn(proxy == null ? Stream.empty() : Stream.of(proxy));

        ObjectProvider<AuthenticationProvider> authProviderProvider = mock(ObjectProvider.class);
        when(authProviderProvider.getIfAvailable()).thenReturn(authProvider);
        when(authProviderProvider.stream()).thenReturn(authProvider == null ? Stream.empty() : Stream.of(authProvider));

        ObjectProvider<UserDetailsService> udsProvider = mock(ObjectProvider.class);
        when(udsProvider.getIfAvailable()).thenReturn(userDetailsService);
        when(udsProvider.stream())
                .thenReturn(userDetailsService == null ? Stream.empty() : Stream.of(userDetailsService));

        ObjectProvider<RequestMappingInfoHandlerMapping> mappingProvider = mock(ObjectProvider.class);
        when(mappingProvider.stream()).thenReturn(Stream.empty());

        SecurityController controller = new SecurityController(
                proxyProvider, authProviderProvider, udsProvider, mappingProvider, env, properties);

        return standaloneSetup(controller).build();
    }

    @Test
    void twoFilterChainsProducedTwoDtoEntriesInOrder() throws Exception {
        SecurityFilterChain chain0 = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, List.of());
        SecurityFilterChain chain1 = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, List.of());

        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of(chain0, chain1));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springSecurityPresent").value(true))
                .andExpect(jsonPath("$.chains.length()").value(2))
                .andExpect(jsonPath("$.chains[0].order").value(0))
                .andExpect(jsonPath("$.chains[1].order").value(1));
    }

    // ── disabled (FilterChainProxy absent) ───────────────────────────────────

    @Test
    void singleChainFiltersListedBySimpleClassName() throws Exception {
        jakarta.servlet.Filter namedFilter = new NamedFilter("SampleFilter");

        SecurityFilterChain chain = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, List.of(namedFilter));
        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of(chain));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chains[0].filters[0]").value("NamedFilter"));
    }

    @Test
    void bootUiSecurityChainsAreHiddenByDefault() throws Exception {
        SecurityFilterChain bootUiChain = new DefaultSecurityFilterChain(new DescribedMatcher("/bootui/**"), List.of());
        SecurityFilterChain applicationChain =
                new DefaultSecurityFilterChain(new DescribedMatcher("/api/**"), List.of());

        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of(bootUiChain, applicationChain));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chains.length()").value(1))
                .andExpect(jsonPath("$.chains[0].order").value(1))
                .andExpect(jsonPath("$.chains[0].requestMatcher").value("/api/**"));
    }

    @Test
    void absentFilterChainProxyReturnsDisabledReport() throws Exception {
        MockMvc mvc = buildMvc(null, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springSecurityPresent").value(false))
                .andExpect(jsonPath("$.chains").isEmpty());
    }

    // ── credential non-disclosure ─────────────────────────────────────────────

    @Test
    void explainEndpointWithAbsentProxyReturnsUnmatchedResult() throws Exception {
        MockMvc mvc = buildMvc(null, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security/explain").param("method", "GET").param("path", "/some/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    void configuredUsernameIsExposedButPasswordIsNot() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.security.user.name", "admin");
        env.setProperty("spring.security.user.password", "super-secret-pw");

        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, null, null, env, new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                // username is exposed for developer convenience
                .andExpect(jsonPath("$.auth.configuredUsername").value("admin"))
                // The response body must not contain the raw password anywhere
                .andExpect(jsonPath("$..super-secret-pw").doesNotExist());
    }

    // ── auth providers listed ─────────────────────────────────────────────────

    @Test
    void configuredUsernameHiddenUnderMetadataOnlyExposure() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.security.user.name", "devuser");

        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of());

        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        MockMvc mvc = buildMvc(proxy, null, null, env, properties);

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.configuredUsername").isEmpty());
    }

    @Test
    void authenticationProviderTypeListed() throws Exception {
        AuthenticationProvider provider = mock(AuthenticationProvider.class);
        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, provider, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.auth.authenticationProviderTypes.length()").value(1));
    }

    // ── best-effort explain ───────────────────────────────────────────────────

    @Test
    void noAuthProvidersResultsInEmptyLists() throws Exception {
        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.authenticationProviderTypes").isEmpty())
                .andExpect(jsonPath("$.auth.userDetailsServiceTypes").isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void explainMatchesChainWhenPathMatches() throws Exception {
        SecurityFilterChain chain = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, List.of());
        FilterChainProxy proxy = mock(FilterChainProxy.class);
        when(proxy.getFilterChains()).thenReturn(List.of(chain));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security/explain").param("method", "GET").param("path", "/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.chainIndex").value(0));
    }

    /**
     * Filter with a fixed simple class name for assertion purposes.
     */
    private static final class NamedFilter implements jakarta.servlet.Filter {
        private final String name;

        NamedFilter(String name) {
            this.name = name;
        }

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest req,
                jakarta.servlet.ServletResponse res,
                jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            chain.doFilter(req, res);
        }
    }

    private record DescribedMatcher(String description) implements RequestMatcher {
        @Override
        public boolean matches(jakarta.servlet.http.HttpServletRequest request) {
            return false;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
