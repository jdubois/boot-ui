package io.github.jdubois.bootui.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

internal data class BootUiBuildFile(val file: VirtualFile, val label: String) {
    override fun toString(): String = label
}

internal object BootUiDependencyInstaller {
    private val names = listOf("pom.xml", "build.gradle", "build.gradle.kts")
    private val excluded = setOf(
        "build", "target", "out", "node_modules", "vendor", "generated", "generated-sources",
        ".gradle", ".gradle-user", ".m2", ".git", ".idea", ".intellijPlatform", ".kotlin",
    )

    fun open(project: Project, owner: Disposable, report: (String) -> Unit) {
        if (project.isDisposed) return
        if (!TrustedProjects.isProjectTrusted(project)) {
            report("Add BootUI dependency is unavailable in an untrusted project. Trust it through the IDE first.")
            return
        }
        if (DumbService.isDumb(project)) {
            report("Wait for IDE indexing to finish, then click Add BootUI dependency again.")
            return
        }
        report("Finding project build files...")
        ReadAction.nonBlocking<List<BootUiBuildFile>> { discover(project) }
            .inSmartMode(project)
            .expireWith(owner)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { candidates ->
                if (!project.isDisposed) {
                    if (candidates.isEmpty()) {
                        report("No supported build file was found in the project's non-generated content roots.")
                    } else {
                        BootUiDependencyDialog(project, candidates, report).show()
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    internal fun discover(project: Project): List<BootUiBuildFile> {
        val scope = ProjectScope.getContentScope(project)
        val index = ProjectFileIndex.getInstance(project)
        return names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
            .filter { eligible(project, it) }
            .distinctBy { it.url }
            .sortedBy { it.path }
            .map { file ->
                val module = index.getModuleForFile(file)?.name ?: "project content"
                val path = project.basePath?.let { base -> file.path.removePrefix("$base/") } ?: file.path
                BootUiBuildFile(file, "$module — $path")
            }
    }

    internal fun eligible(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || !file.isInLocalFileSystem || file.isDirectory || file.name !in names) return false
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(file) || index.isExcluded(file) || index.isInGeneratedSources(file)) return false
        val root = index.getContentRootForFile(file) ?: return false
        var directory = file.parent
        while (directory != null) {
            if (directory.name in excluded) return false
            if (directory == root) break
            directory = directory.parent
        }
        return directory == root
    }
}

internal class BootUiDependencyDialog(
    private val project: Project,
    candidates: List<BootUiBuildFile>,
    private val report: (String) -> Unit,
) : DialogWrapper(project) {
    private val buildFile = ComboBox(candidates.toTypedArray())
    private val integration = ComboBox(BootUiIntegration.entries.toTypedArray())
    private val version = JTextField(BootUiDependencyEdit.DEFAULT_VERSION)
    private val previewButton = JButton("Preview exact change")
    private val before = JTextArea()
    private val after = JTextArea()
    private val message = JTextArea("Select the application's module/build file and integration, then preview.", 3, 30)
    private var reviewed: DependencyPreview? = null
    private var reviewedDocument: Document? = null
    private var reviewedFile: VirtualFile? = null

    init {
        title = "Add BootUI dependency"
        setOKButtonText("Add dependency")
        init()
        setOKActionEnabled(false)
        buildFile.addActionListener { invalidatePreview() }
        integration.addActionListener { invalidatePreview() }
        version.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = invalidatePreview()
        })
        previewButton.addActionListener { preview() }
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridLayout(0, 2, JBUI.scale(8), JBUI.scale(6)))
        form.add(JBLabel("Application module / build file"))
        form.add(buildFile)
        form.add(JBLabel("Integration"))
        form.add(integration)
        form.add(JBLabel("Stable BootUI version"))
        form.add(version)
        message.isEditable = false
        message.lineWrap = true
        message.wrapStyleWord = true
        message.background = form.background
        form.add(previewButton)
        form.add(message)
        val warning = JTextArea(
            "Adds only a dependency to the selected build file. Does not change profiles or production activation, " +
                "reload the build, download dependencies, build, run, or connect.\n" +
                "Choose the correct application module. Existing activation rules still apply. " +
                "Your IDE's normal build auto-reload settings may independently trigger repository access.",
        )
        warning.isEditable = false
        warning.lineWrap = true
        warning.wrapStyleWord = true
        warning.background = form.background
        val top = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        top.add(form, BorderLayout.NORTH)
        top.add(warning, BorderLayout.CENTER)
        before.isEditable = false
        after.isEditable = false
        fun side(label: String, text: JTextArea): JPanel = JPanel(BorderLayout()).apply {
            add(JBLabel(label), BorderLayout.NORTH)
            add(JScrollPane(text), BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side("Current document (including unsaved edits)", before), side("After confirmation", after))
        split.resizeWeight = 0.5
        return JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(10))).apply {
            add(top, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(950), JBUI.scale(600))
        }
    }

    private fun invalidatePreview() {
        reviewed = null
        reviewedDocument = null
        reviewedFile = null
        before.text = ""
        after.text = ""
        setOKActionEnabled(false)
        message.text = "Preview again before adding."
    }

    internal fun preview() {
        invalidatePreview()
        val file = (buildFile.selectedItem as? BootUiBuildFile)?.file ?: return
        if (project.isDisposed || DumbService.isDumb(project)) {
            message.text = "Wait for indexing to finish."
            return
        }
        if (!BootUiDependencyInstaller.eligible(project, file)) {
            message.text = "The build file is no longer in supported project content."
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document == null) {
            message.text = "The IDE could not open this build file as a text document."
            return
        }
        try {
            val result = BootUiDependencyEdit.preview(
                project, file.name, document, integration.selectedItem as BootUiIntegration, version.text.trim(),
            )
            reviewed = result
            reviewedDocument = document
            reviewedFile = file
            before.text = result.before
            after.text = result.after
            before.caretPosition = 0
            after.caretPosition = 0
            message.text = "Review the exact resulting file, then Add dependency or Cancel."
            setOKActionEnabled(true)
        } catch (exception: DependencyEditException) {
            message.text = exception.message
        }
    }

    override fun doOKAction() {
        val result = reviewed ?: return
        val document = reviewedDocument ?: return
        val file = reviewedFile ?: return
        if (project.isDisposed) { close(CANCEL_EXIT_CODE); return }
        if (!TrustedProjects.isProjectTrusted(project) || DumbService.isDumb(project) ||
            !BootUiDependencyInstaller.eligible(project, file)) {
            invalidatePreview()
            message.text = "Project trust, indexing, or file scope changed. No edit was made."
            return
        }
        if (!result.isCurrent(document)) {
            invalidatePreview()
            message.text = "The document changed after preview. Preview and review again; no edit was made."
            return
        }
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) {
            message.text = "The build file is read-only. No edit was made."
            return
        }
        if (!BootUiDependencyEdit.apply(project, document, result) {
                !DumbService.isDumb(project) && BootUiDependencyInstaller.eligible(project, file) &&
                    FileDocumentManager.getInstance().getDocument(file) === document
            }) {
            invalidatePreview()
            message.text = "The document changed or is read-only. Preview again; no edit was made."
            return
        }
        report("BootUI dependency added (undoable). Reload the build through the IDE when ready; no run or connection was requested.")
        super.doOKAction()
    }

    override fun doCancelAction() {
        report("Dependency addition cancelled. No build file was changed.")
        super.doCancelAction()
    }
}
