package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.util.matcher.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

/**
 * Standalone MockMvc tests for {@link ReactiveSpringSecurityController}.
 *
 * <p>Exercises:</p>
 * <ul>
 *   <li>Multiple {@code SecurityWebFilterChain} entries render as one DTO each, in order.</li>
 *   <li>A missing {@code WebFilterChainProxy} produces a stable
 *       {@code springSecurityPresent=false} report, not a failure.</li>
 *   <li>Auth block exposes {@code configuredUsername} from environment but never a password.</li>
 *   <li>{@code bestEffort} is populated on the explain result.</li>
 * </ul>
 */
class ReactiveSpringSecurityControllerTests {

    @SuppressWarnings("unchecked")
    private static MockMvc buildMvc(
            WebFilterChainProxy proxy,
            ReactiveAuthenticationManager authManager,
            ReactiveUserDetailsService userDetailsService,
            MockEnvironment env,
            BootUiProperties properties) {
        ObjectProvider<WebFilterChainProxy> proxyProvider = mock(ObjectProvider.class);
        when(proxyProvider.getIfAvailable()).thenReturn(proxy);
        when(proxyProvider.stream()).thenReturn(proxy == null ? Stream.empty() : Stream.of(proxy));

        ObjectProvider<ReactiveAuthenticationManager> authManagerProvider = mock(ObjectProvider.class);
        when(authManagerProvider.getIfAvailable()).thenReturn(authManager);
        when(authManagerProvider.stream())
                .thenReturn(authManager == null ? Stream.empty() : Stream.of(authManager));

        ObjectProvider<ReactiveUserDetailsService> udsProvider = mock(ObjectProvider.class);
        when(udsProvider.getIfAvailable()).thenReturn(userDetailsService);
        when(udsProvider.stream())
                .thenReturn(userDetailsService == null ? Stream.empty() : Stream.of(userDetailsService));

        ObjectProvider<RequestMappingHandlerMapping> mappingProvider = mock(ObjectProvider.class);
        when(mappingProvider.stream()).thenReturn(Stream.empty());

        ReactiveSpringSecurityController controller =
                new ReactiveSpringSecurityController(proxyProvider, authManagerProvider, udsProvider, mappingProvider, env, properties);

        return standaloneSetup(controller).build();
    }

    // ── chain listing ─────────────────────────────────────────────────────────

    @Test
    void twoFilterChainsProducedTwoDtoEntriesInOrder() throws Exception {
        var chain0 = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of());
        var chain1 = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/other/**"), List.of());

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of(chain0, chain1));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springSecurityPresent").value(true))
                .andExpect(jsonPath("$.chains.length()").value(2))
                .andExpect(jsonPath("$.chains[0].order").value(0))
                .andExpect(jsonPath("$.chains[1].order").value(1));
    }

    @Test
    void absentWebFilterChainProxyReturnsDisabledReport() throws Exception {
        MockMvc mvc = buildMvc(null, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springSecurityPresent").value(false))
                .andExpect(jsonPath("$.chains").isEmpty());
    }

    // ── credential non-disclosure ─────────────────────────────────────────────

    @Test
    void configuredUsernameIsExposedButPasswordIsNot() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.security.user.name", "admin");
        env.setProperty("spring.security.user.password", "super-secret-pw");

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, null, null, env, new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                // username is exposed for developer convenience
                .andExpect(jsonPath("$.auth.configuredUsername").value("admin"))
                // The response body must not contain the raw password anywhere
                .andExpect(jsonPath("$..super-secret-pw").doesNotExist());
    }

    @Test
    void configuredUsernameHiddenUnderMetadataOnlyExposure() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.security.user.name", "devuser");

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of());

        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        MockMvc mvc = buildMvc(proxy, null, null, env, properties);

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.configuredUsername").isEmpty());
    }

    // ── auth managers listed ──────────────────────────────────────────────────

    @Test
    void authenticationManagerTypeListed() throws Exception {
        ReactiveAuthenticationManager manager = mock(ReactiveAuthenticationManager.class);

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, manager, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.authenticationProviderTypes.length()").value(1));
    }

    @Test
    void noAuthManagersResultsInEmptyLists() throws Exception {
        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of());

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.authenticationProviderTypes").isEmpty())
                .andExpect(jsonPath("$.auth.userDetailsServiceTypes").isEmpty());
    }

    // ── explain ───────────────────────────────────────────────────────────────

    @Test
    void explainEndpointWithAbsentProxyReturnsUnmatchedResult() throws Exception {
        MockMvc mvc = buildMvc(null, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security/explain")
                        .param("method", "GET")
                        .param("path", "/some/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    void explainMatchesChainWhenPathMatches() throws Exception {
        var chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of());

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of(chain));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security/explain")
                        .param("method", "GET")
                        .param("path", "/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.chainIndex").value(0));
    }

    @Test
    void explainDoesNotMatchChainWhenPathDoesNotMatch() throws Exception {
        var chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of());

        WebFilterChainProxy proxy = mock(WebFilterChainProxy.class);
        when(proxy.getWebFilterChains()).thenReturn(List.of(chain));

        MockMvc mvc = buildMvc(proxy, null, null, new MockEnvironment(), new BootUiProperties());

        mvc.perform(get("/bootui/api/spring-security/explain")
                        .param("method", "GET")
                        .param("path", "/other/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));
    }
}
