package io.github.jdubois.bootui.jetbrains

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BootUiEndpointTest {
    @Test
    fun `combines loopback application root and independently configured mounts`() {
        val endpoint = BootUiEndpoint.parse(
            "http://localhost:8081/services/orders/",
            "/diagnostics/api",
            "/console",
        )

        assertEquals("http://localhost:8081/services/orders/diagnostics/api/overview", endpoint.overviewUri.toString())
        assertEquals("http://localhost:8081/services/orders/diagnostics/api/panels", endpoint.panelsUri.toString())
        assertEquals("http://localhost:8081/services/orders/console", endpoint.consoleUri.toString())
    }

    @Test
    fun `accepts numeric IPv4 and IPv6 loopback only`() {
        assertEquals(
            "http://127.0.0.2:9000/bootui/api/overview",
            BootUiEndpoint.parse("http://127.0.0.2:9000", "/bootui/api", "/bootui").overviewUri.toString(),
        )
        assertEquals(
            "http://[::1]:9000/bootui/api/overview",
            BootUiEndpoint.parse("http://[::1]:9000", "/bootui/api", "/bootui").overviewUri.toString(),
        )
        assertFailsWith<EndpointInputException> {
            BootUiEndpoint.parse("http://192.168.1.8:9000", "/bootui/api", "/bootui")
        }
        assertFailsWith<EndpointInputException> {
            BootUiEndpoint.parse("http://localhost.evil.example:9000", "/bootui/api", "/bootui")
        }
    }

    @Test
    fun `rejects credentials query fragments and unsafe paths`() {
        listOf(
            "http://user:password@localhost:8080",
            "http://localhost:8080?debug=true",
            "http://localhost:8080#fragment",
            "http://localhost:8080/../outside",
            "http://localhost:8080/%2e%2e/outside",
            "https://example.com",
        ).forEach { root ->
            assertFailsWith<EndpointInputException> {
                BootUiEndpoint.parse(root, "/bootui/api", "/bootui")
            }
        }
        listOf("relative", "/bootui/../outside", "/bootui/%2f..", "/bootui?x=1", "/bootui#frag").forEach { path ->
            assertFailsWith<EndpointInputException> {
                BootUiEndpoint.parse("http://localhost:8080", path, "/bootui")
            }
        }
    }
}
