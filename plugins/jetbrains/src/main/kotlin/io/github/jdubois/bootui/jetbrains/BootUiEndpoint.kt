package io.github.jdubois.bootui.jetbrains

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.URISyntaxException

internal data class BootUiEndpoint(
    val applicationRoot: URI,
    val apiPath: String,
    val uiPath: String,
) {
    val overviewUri: URI
        get() = resolve(apiPath, "overview")

    val panelsUri: URI
        get() = resolve(apiPath, "panels")

    val consoleUri: URI
        get() = resolve(uiPath, "")

    private fun resolve(path: String, suffix: String): URI {
        val fullPath = listOf(applicationRoot.rawPath.trim('/'), path.trim('/'), suffix.trim('/'))
            .filter(String::isNotEmpty)
            .joinToString("/", prefix = "/")
        return URI(
            applicationRoot.scheme,
            null,
            applicationRoot.host,
            applicationRoot.port,
            fullPath,
            null,
            null,
        )
    }

    companion object {
        fun parse(applicationUrl: String, apiPath: String, uiPath: String): BootUiEndpoint {
            val root = parseApplicationRoot(applicationUrl)
            return BootUiEndpoint(
                applicationRoot = root,
                apiPath = parseMountPath(apiPath, "API path"),
                uiPath = parseMountPath(uiPath, "UI path"),
            )
        }

        private fun parseApplicationRoot(value: String): URI {
            val uri = try {
                URI(value.trim())
            } catch (_: URISyntaxException) {
                throw EndpointInputException("Application URL is not a valid URL.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
                ?: throw EndpointInputException("Application URL must use localhost or a numeric loopback address.")
            if (!uri.isAbsolute || uri.scheme.lowercase() !in setOf("http", "https") ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65535 || uri.port == 0 || !isLoopbackHost(host)
            ) {
                throw EndpointInputException("Application URL must be HTTP(S) on localhost or a numeric loopback address, without credentials, query, or fragment.")
            }
            val path = uri.rawPath.orEmpty()
            validatePath(path, "Application URL path", allowEmpty = true)
            return URI(uri.scheme.lowercase(), null, host, uri.port, path.trimEnd('/'), null, null)
        }

        private fun parseMountPath(value: String, label: String): String {
            val path = value.trim()
            validatePath(path, label, allowEmpty = false)
            return path.trimEnd('/').ifEmpty { "/" }
        }

        private fun validatePath(path: String, label: String, allowEmpty: Boolean) {
            if ((!allowEmpty && !path.startsWith('/')) ||
                path.any { it.isWhitespace() || it.isISOControl() || it == '\\' || it == '%' || it == '?' || it == '#' } ||
                path.split('/').any { it == "." || it == ".." } ||
                path.contains("//")
            ) {
                throw EndpointInputException("$label must be an absolute local path without traversal, query, or encoded segments.")
            }
        }

        private fun isLoopbackHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true)) return true

            if (host.contains(':')) {
                if (host.contains('%')) return false
                return try {
                    val address = InetAddress.getByName(host)
                    address is Inet6Address && address.isLoopbackAddress
                } catch (_: UnknownHostException) {
                    false
                }
            }

            val octets = host.split('.')
            if (octets.size != 4 || octets.any {
                    it.isEmpty() || (it.length > 1 && it.startsWith('0')) || it.any { char -> !char.isDigit() }
                }
            ) return false
            val parsed = octets.map { it.toIntOrNull() ?: return false }
            return parsed.all { it in 0..255 } && parsed[0] == 127
        }
    }
}

internal class EndpointInputException(message: String) : IllegalArgumentException(message)
