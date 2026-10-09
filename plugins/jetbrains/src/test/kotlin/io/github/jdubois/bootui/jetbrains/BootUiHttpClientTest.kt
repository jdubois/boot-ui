package io.github.jdubois.bootui.jetbrains

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BootUiHttpClientTest {
    @Test
    fun `sends only GET with JSON accept and optional bearer token`() = withServer { server ->
        server.createContext("/bootui/api/overview") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, """{"applicationName":"local"}""")
        }

        val body = runBlocking {
            BootUiHttpClient().get(
                URI("http://127.0.0.1:${server.address.port}/bootui/api/overview"),
                "test-token",
            )
        }

        assertEquals("""{"applicationName":"local"}""", body)
    }

    @Test
    fun `omits authorization without a token`() = withServer { server ->
        server.createContext("/overview") { exchange ->
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, "{}")
        }
        runBlocking {
            val uri = URI("http://127.0.0.1:${server.address.port}/overview")
            BootUiHttpClient().get(uri, null)
            BootUiHttpClient().get(uri, " ")
        }
    }

    @Test
    fun `does not follow redirects or surface response bodies`() = withServer { server ->
        server.createContext("/bootui/api/overview") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
            respond(exchange, 302, "password=must-not-leak")
        }
        val exception = assertFailsWith<ApiTransportException> {
            runBlocking {
                BootUiHttpClient().get(
                    URI("http://127.0.0.1:${server.address.port}/bootui/api/overview"),
                    null,
                )
            }
        }

        assertEquals(ApiFailure.REDIRECT, exception.failure)
        assertFalse(exception.message.orEmpty().contains("must-not-leak"))
    }

    @Test
    fun `rejects non JSON and oversized response bodies`() = withServer { server ->
        server.createContext("/plain") { exchange -> respond(exchange, 200, "not-json", "text/plain") }
        server.createContext("/large") { exchange -> respond(exchange, 200, "123456789") }
        val client = BootUiHttpClient(maxResponseBytes = 8, requestTimeout = Duration.ofSeconds(2))

        val contentType = assertFailsWith<ApiTransportException> {
            runBlocking { client.get(URI("http://127.0.0.1:${server.address.port}/plain"), null) }
        }
        val tooLarge = assertFailsWith<ApiTransportException> {
            runBlocking { client.get(URI("http://127.0.0.1:${server.address.port}/large"), null) }
        }

        assertEquals(ApiFailure.CONTENT_TYPE, contentType.failure)
        assertEquals(ApiFailure.RESPONSE_TOO_LARGE, tooLarge.failure)
    }

    @Test
    fun `maps server errors without exposing raw response`() = withServer { server ->
        server.createContext("/failure") { exchange ->
            respond(exchange, 500, "private server details")
        }

        val exception = assertFailsWith<ApiTransportException> {
            runBlocking { BootUiHttpClient().get(URI("http://127.0.0.1:${server.address.port}/failure"), null) }
        }

        assertEquals(ApiFailure.SERVER_ERROR, exception.failure)
        assertFalse(exception.message.orEmpty().contains("private"))
    }

    @Test
    fun `enforces request timeout`() = withServer { server ->
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            try {
                respond(exchange, 200, "{}")
            } catch (_: IOException) {
                Unit
            }
        }

        assertFailsWith<HttpTimeoutException> {
            runBlocking {
                BootUiHttpClient(requestTimeout = Duration.ofMillis(75))
                    .get(URI("http://127.0.0.1:${server.address.port}/slow"), null)
            }
        }
    }

    private fun withServer(test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        try {
            server.start()
            test(server)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
