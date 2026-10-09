package io.github.jdubois.bootui.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

internal class BootUiToolWindowPanel(
    private val project: Project,
    private val service: BootUiProjectService,
) : JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8))), Disposable {
    private val applicationUrl = JTextField()
    private val apiPath = JTextField()
    private val uiPath = JTextField()
    private val token = JPasswordField()
    private val connectButton = JButton("Connect")
    private val runButton = JButton("Run application")
    private val addDependencyButton = JButton("Add BootUI dependency")
    private val refreshButton = JButton("Refresh")
    private val openButton = JButton("Open Console")
    private val copyButton = JButton("Copy AI context")
    private val forgetTokenButton = JButton("Forget token")
    private val status = JBLabel("Not connected")
    private val overview = JTextArea()
    private val panels = JTextArea()
    private val disposed = AtomicBoolean(false)
    private val listener = service.addListener(::render)
    private var lastSnapshot: BootUiSnapshot? = null

    init {
        val stored = service.settings()
        applicationUrl.text = stored.applicationUrl
        apiPath.text = stored.apiPath
        uiPath.text = stored.uiPath

        val form = JPanel(java.awt.GridLayout(0, 2, JBUI.scale(8), JBUI.scale(4)))
        form.border = JBUI.Borders.empty(8)
        form.add(JLabel("Application URL"))
        form.add(applicationUrl)
        form.add(JLabel("API path"))
        form.add(apiPath)
        form.add(JLabel("UI path"))
        form.add(uiPath)
        form.add(JLabel("Access token (optional; stored in Password Safe)"))
        form.add(token)

        val buttons = JPanel(java.awt.GridLayout(0, 2, JBUI.scale(6), JBUI.scale(4)))
        buttons.add(connectButton)
        buttons.add(runButton)
        buttons.add(addDependencyButton)
        buttons.add(refreshButton)
        buttons.add(openButton)
        buttons.add(copyButton)
        buttons.add(forgetTokenButton)

        val controls = JPanel(BorderLayout())
        controls.add(form, BorderLayout.CENTER)
        controls.add(buttons, BorderLayout.SOUTH)
        add(controls, BorderLayout.NORTH)

        overview.isEditable = false
        overview.lineWrap = true
        overview.wrapStyleWord = true
        panels.isEditable = false
        panels.lineWrap = true
        panels.wrapStyleWord = true
        val results = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(6)))
        results.border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        results.add(status, BorderLayout.NORTH)
        val details = JPanel(java.awt.GridLayout(2, 1, 0, JBUI.scale(6)))
        overview.border = BorderFactory.createTitledBorder("Application overview")
        panels.border = BorderFactory.createTitledBorder("Panel availability")
        details.add(JScrollPane(overview))
        details.add(JScrollPane(panels))
        results.add(details, BorderLayout.CENTER)
        add(results, BorderLayout.CENTER)

        connectButton.addActionListener { connect() }
        runButton.toolTipText = "Run the configuration selected in IntelliJ's Run toolbar; this does not install or enable BootUI."
        runButton.addActionListener {
            status.text = BootUiApplicationRunner.runSelected(project)
        }
        addDependencyButton.toolTipText = "Review and add a dependency to an application build file; does not activate or run BootUI."
        addDependencyButton.addActionListener {
            BootUiDependencyInstaller.open(project, this) { result ->
                if (!disposed.get()) status.text = result
            }
        }
        refreshButton.addActionListener { connect() }
        openButton.addActionListener { openConsole() }
        copyButton.addActionListener { copyContext() }
        forgetTokenButton.addActionListener {
            token.text = ""
            status.text = "Removing the saved access token..."
            service.forgetToken {
                if (!disposed.get()) {
                    status.text = "Saved access token removed from Password Safe."
                }
            }
        }
        render(service.currentState())
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            listener.dispose()
        }
    }

    private fun connect() {
        val password = token.password
        token.text = ""
        service.connect(applicationUrl.text, apiPath.text, uiPath.text, password)
    }

    private fun openConsole() {
        try {
            val endpoint = BootUiEndpoint.parse(applicationUrl.text, apiPath.text, uiPath.text)
            BrowserUtil.browse(endpoint.consoleUri)
        } catch (exception: EndpointInputException) {
            status.text = exception.message
        }
    }

    private fun copyContext() {
        val snapshot = lastSnapshot ?: return
        val context = try {
            BootUiContextExporter.export(snapshot)
        } catch (_: IllegalStateException) {
            status.text = "The overview is too large to export safely."
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(context))
        status.text = "Bounded overview context copied. Nothing was sent to an AI provider."
    }

    private fun render(state: ConnectionState) {
        if (disposed.get() || project.isDisposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { render(state) }
            return
        }
        when (state) {
            ConnectionState.Disconnected -> {
                status.text = "Not connected. Connect to fetch the local BootUI overview."
                overview.text = ""
                panels.text = ""
                lastSnapshot = null
            }
            ConnectionState.Connecting -> {
                status.text = "Connecting to the local BootUI API..."
                lastSnapshot = null
            }
            is ConnectionState.Failed -> {
                status.text = state.message
                lastSnapshot = null
            }
            is ConnectionState.Connected -> {
                lastSnapshot = state.snapshot
                status.text = "Connected to BootUI"
                overview.text = formatOverview(state.snapshot.overview)
                panels.text = formatPanels(state.snapshot)
            }
        }
        val connected = state is ConnectionState.Connected
        refreshButton.isEnabled = state !is ConnectionState.Connecting
        connectButton.isEnabled = state !is ConnectionState.Connecting
        openButton.isEnabled = true
        copyButton.isEnabled = connected
    }

    private fun formatOverview(value: Overview): String = buildList {
        add("Application: ${value.applicationName ?: "Unavailable"}")
        add("BootUI: ${value.bootUiVersion ?: "Unknown"}")
        add("Framework: ${listOfNotNull(value.frameworkName, value.frameworkVersion).joinToString(" ").ifEmpty { "Unknown" }}")
        add("Java: ${listOfNotNull(value.javaVersion, value.javaVendor).joinToString(" ").ifEmpty { "Unknown" }}")
        add("Active profiles: ${value.activeProfiles.take(10).joinToString(", ").ifEmpty { "None reported" }}")
        add("Server port: ${value.serverPort?.toString() ?: "Not reported"}")
        add("BootUI active: ${value.activationEnabled?.toString() ?: "Not reported"}")
        add("Localhost only: ${value.localhostOnly?.toString() ?: "Not reported"}")
    }.joinToString("\n")

    private fun formatPanels(snapshot: BootUiSnapshot): String = buildList {
        add("Platform: ${snapshot.platform ?: "Unknown"}")
        if (snapshot.panels.isEmpty()) {
            add("No panel availability was reported.")
        } else {
            snapshot.panels.forEach { panel ->
                val availability = if (panel.available) "available" else "unavailable"
                val enabled = if (panel.enabled) "enabled" else "disabled"
                val readOnly = if (panel.readOnly) "read-only" else "actions allowed"
                add("${panel.id}: $availability, $enabled, $readOnly")
            }
        }
    }.joinToString("\n")
}
