package io.github.jdubois.bootui.jetbrains

import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import kotlinx.coroutines.future.await

internal class BootUiHttpClient(
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    init {
        require(maxResponseBytes > 0)
        require(!requestTimeout.isNegative && !requestTimeout.isZero)
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(NO_PROXY)
        .build()

    suspend fun get(uri: URI, bearerToken: String?): String {
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "BootUI-JetBrains-Companion")
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    header("Authorization", "Bearer $bearerToken")
                }
            }
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandler { info ->
            HttpResponse.BodySubscribers.mapping(
                BoundedBodySubscriber(maxResponseBytes),
            ) { bytes -> bytes.toString(StandardCharsets.UTF_8) }
        }).await()

        if (response.statusCode() !in 200..299) {
            throw ApiTransportException.forStatus(response.statusCode())
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
            .substringBefore(';')
            .trim()
            .lowercase()
        if (contentType != "application/json" &&
            !(contentType.startsWith("application/") && contentType.endsWith("+json"))
        ) {
            throw ApiTransportException(ApiFailure.CONTENT_TYPE)
        }
        return response.body()
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024
        private val CONNECT_TIMEOUT = Duration.ofSeconds(3)
        private val DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(8)
        private val NO_PROXY = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, failure: java.io.IOException) = Unit
        }
    }
}

private class BoundedBodySubscriber(
    private val maxBytes: Int,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val body = CompletableFuture<ByteArray>()
    private val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    private var subscription: Flow.Subscription? = null
    private var receivedBytes = 0

    override fun getBody(): CompletableFuture<ByteArray> = body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        for (buffer in items) {
            val count = buffer.remaining()
            if (receivedBytes + count > maxBytes) {
                subscription?.cancel()
                body.completeExceptionally(ApiTransportException(ApiFailure.RESPONSE_TOO_LARGE))
                return
            }
            val bytes = ByteArray(count)
            buffer.get(bytes)
            output.write(bytes)
            receivedBytes += count
        }
        subscription?.request(1)
    }

    override fun onError(throwable: Throwable) {
        body.completeExceptionally(throwable)
    }

    override fun onComplete() {
        body.complete(output.toByteArray())
    }
}

internal enum class ApiFailure {
    REDIRECT,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    SERVER_ERROR,
    HTTP_ERROR,
    CONTENT_TYPE,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    TRANSPORT,
}

internal class ApiTransportException(val failure: ApiFailure) : Exception(failure.message) {
    companion object {
        fun forStatus(status: Int): ApiTransportException = when {
            status in 300..399 -> ApiTransportException(ApiFailure.REDIRECT)
            status == 401 -> ApiTransportException(ApiFailure.UNAUTHORIZED)
            status == 403 -> ApiTransportException(ApiFailure.FORBIDDEN)
            status == 404 -> ApiTransportException(ApiFailure.NOT_FOUND)
            status >= 500 -> ApiTransportException(ApiFailure.SERVER_ERROR)
            else -> ApiTransportException(ApiFailure.HTTP_ERROR)
        }
    }
}

private val ApiFailure.message: String
    get() = when (this) {
        ApiFailure.REDIRECT -> "BootUI redirected the request. Redirects are not followed."
        ApiFailure.UNAUTHORIZED -> "BootUI requires a token. Check the optional access token."
        ApiFailure.FORBIDDEN -> "BootUI rejected the request. Check its access policy."
        ApiFailure.NOT_FOUND -> "The BootUI API endpoint was not found. Check the API path."
        ApiFailure.SERVER_ERROR -> "BootUI returned a server error."
        ApiFailure.HTTP_ERROR -> "BootUI returned an HTTP error."
        ApiFailure.CONTENT_TYPE -> "BootUI did not return JSON."
        ApiFailure.RESPONSE_TOO_LARGE -> "BootUI response exceeded the 256 KiB safety limit."
        ApiFailure.TIMEOUT -> "BootUI did not respond before the request timeout."
        ApiFailure.TRANSPORT -> "Could not connect to BootUI on the local endpoint."
    }
