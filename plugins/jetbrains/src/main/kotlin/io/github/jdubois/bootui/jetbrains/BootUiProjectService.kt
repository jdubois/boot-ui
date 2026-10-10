package io.github.jdubois.bootui.jetbrains

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val snapshot: BootUiSnapshot) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

internal data class BootUiSettingsState(
    var applicationUrl: String = "http://localhost:8080",
    var apiPath: String = "/bootui/api",
    var uiPath: String = "/bootui",
)

@Service(Service.Level.PROJECT)
@State(name = "BootUiJetBrainsSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class BootUiProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : PersistentStateComponent<BootUiSettingsState>, Disposable {
    private val listeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()
    private val requestSequence = AtomicLong()
    private val httpClient = BootUiHttpClient()
    @Volatile private var activeJob: Job? = null
    @Volatile private var disposed = false
    private var settings = BootUiSettingsState()
    @Volatile private var currentState: ConnectionState = ConnectionState.Disconnected

    override fun getState(): BootUiSettingsState = settings

    override fun loadState(state: BootUiSettingsState) {
        settings = state
    }

    fun settings(): BootUiSettingsState = settings.copy()

    fun currentState(): ConnectionState = currentState

    fun addListener(listener: (ConnectionState) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    fun connect(applicationUrl: String, apiPath: String, uiPath: String, token: CharArray) {
        activeJob?.cancel()
        val requestId = requestSequence.incrementAndGet()
        val endpoint = try {
            BootUiEndpoint.parse(applicationUrl, apiPath, uiPath)
        } catch (exception: EndpointInputException) {
            publish(ConnectionState.Failed(exception.message ?: "Check the local endpoint settings."))
            token.fill('\u0000')
            return
        }
        if (token.size > MAX_TOKEN_LENGTH || token.any { it.isISOControl() || it.isWhitespace() }) {
            publish(ConnectionState.Failed("The access token must be a single-line value of at most 4,096 characters."))
            token.fill('\u0000')
            return
        }
        settings = BootUiSettingsState(
            applicationUrl = applicationUrl.trim(),
            apiPath = apiPath.trim(),
            uiPath = uiPath.trim(),
        )
        publish(ConnectionState.Connecting)
        val tokenText = token.concatToString()
        token.fill('\u0000')
        activeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val secret = withContext(Dispatchers.IO) {
                    if (tokenText.isNotBlank()) {
                        storeToken(tokenText)
                        tokenText
                    } else {
                        readToken()
                    }
                }
                val overview = BootUiApiParser.parseOverview(httpClient.get(endpoint.overviewUri, secret))
                val (platform, panels) = BootUiApiParser.parsePanels(httpClient.get(endpoint.panelsUri, secret))
                publishIfCurrent(requestId, ConnectionState.Connected(BootUiSnapshot(overview, platform, panels)))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiTransportException) {
                publishIfCurrent(requestId, ConnectionState.Failed(exception.message ?: "BootUI request failed."))
            } catch (exception: ApiResponseException) {
                publishIfCurrent(requestId, ConnectionState.Failed(exception.message ?: "BootUI returned invalid data."))
            } catch (exception: HttpTimeoutException) {
                publishIfCurrent(requestId, ConnectionState.Failed("BootUI did not respond before the request timeout."))
            } catch (exception: IOException) {
                publishIfCurrent(requestId, ConnectionState.Failed("Could not connect to BootUI on the local endpoint."))
            }
        }
    }

    fun forgetToken(onComplete: () -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            PasswordSafe.instance.set(tokenAttributes(), null)
            if (!disposed && !project.isDisposed) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) {
                        onComplete()
                    }
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        requestSequence.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        listeners.clear()
    }

    private fun storeToken(token: String) {
        PasswordSafe.instance.setPassword(tokenAttributes(), token)
    }

    private fun readToken(): String? = PasswordSafe.instance
        .getPassword(tokenAttributes())
        ?.takeIf {
            it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH &&
                it.none { char -> char.isISOControl() || char.isWhitespace() }
        }

    private fun tokenAttributes() =
        CredentialAttributes("BootUI JetBrains companion:${project.locationHash}")

    private fun publishIfCurrent(requestId: Long, state: ConnectionState) {
        if (requestSequence.get() == requestId && !disposed && !project.isDisposed) {
            publish(state)
        }
    }

    private fun publish(state: ConnectionState) {
        if (disposed || project.isDisposed) return
        currentState = state
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) {
                if (currentState == state) {
                    listeners.forEach { listener -> listener(state) }
                }
            }
        }
    }

    private companion object {
        const val MAX_TOKEN_LENGTH = 4096
    }
}
