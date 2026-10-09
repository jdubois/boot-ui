package io.github.jdubois.bootui.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootUiDependencyEditTest : BasePlatformTestCase() {
    private fun preview(name: String, text: String, integration: BootUiIntegration = BootUiIntegration.MVC): DependencyPreview {
        myFixture.configureByText(name, text)
        return BootUiDependencyEdit.preview(project, name, myFixture.editor.document, integration, "1.20.0")
    }

    private fun editMaven(text: String): XmlFile {
        val result = preview("pom.xml", text)
        assertTrue(BootUiDependencyEdit.apply(project, myFixture.editor.document, result))
        assertEquals(result.after, myFixture.editor.document.text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return myFixture.file as XmlFile
    }

    fun testMavenDefaultNamespaceAndCommentPreserved() {
        val file = editMaven(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <dependencies>
    <!-- keep this comment: bootui-spring-boot-starter -->
    <dependency><groupId>example</groupId><artifactId>sample</artifactId></dependency>
  </dependencies>
</project>""",
        )
        val dependencies = file.rootTag!!.findFirstSubTag("dependencies")!!
        assertEquals(2, dependencies.subTags.size)
        assertEquals("bootui-spring-boot-starter", dependencies.subTags.last().findFirstSubTag("artifactId")!!.value.trimmedText)
        assertContains(file.text, "<!-- keep this comment: bootui-spring-boot-starter -->")
    }

    fun testMavenPrefixedNamespaceWithoutDependencies() {
        val file = editMaven(
            """<m:project xmlns:m="http://maven.apache.org/POM/4.0.0"><m:modelVersion>4.0.0</m:modelVersion></m:project>""",
        )
        val dependencies = file.rootTag!!.subTags.single { it.localName == "dependencies" }
        assertEquals("m:dependencies", dependencies.name)
        assertEquals("http://maven.apache.org/POM/4.0.0", dependencies.subTags.single().namespace)
        assertEquals("m:version", dependencies.subTags.single().subTags.last().name)
    }

    fun testMavenNestedDependenciesAreNeverInsertionTargets() {
        val nested = """<dependencyManagement><dependencies><dependency><groupId>example</groupId><artifactId>managed</artifactId></dependency></dependencies></dependencyManagement>
<profiles><profile><id>dev</id><dependencies><dependency><groupId>example</groupId><artifactId>profile</artifactId></dependency></dependencies></profile></profiles>
<build><plugins><plugin><artifactId>plugin</artifactId><dependencies><dependency><groupId>example</groupId><artifactId>plugin-dep</artifactId></dependency></dependencies></plugin></plugins></build>"""
        val file = editMaven("<project>$nested</project>")
        val root = file.rootTag!!
        assertEquals(1, root.subTags.single { it.localName == "dependencies" }.subTags.size)
        assertContains(file.text, nested)
    }

    fun testMavenSelfClosingElementsAndEmptyDependencyComment() {
        for (text in listOf("<project/>", "<project><dependencies/></project>", "<project><dependencies><!-- keep --></dependencies></project>")) {
            val file = editMaven(text)
            assertEquals(1, file.rootTag!!.findFirstSubTag("dependencies")!!.subTags.size)
            if (text.contains("<!-- keep -->")) assertContains(file.text, "<!-- keep -->")
        }
    }

    fun testMavenExistingSameDifferentManagedAndProfileDependenciesRefuseEdits() {
        for (artifact in BootUiIntegration.entries.map { it.artifact }) {
            val dependency = "<dependency><groupId>${BootUiDependencyEdit.GROUP}</groupId><artifactId>$artifact</artifactId><version>1.0.0</version></dependency>"
            for (section in listOf(
                "<dependencies>$dependency</dependencies>",
                "<dependencyManagement><dependencies>$dependency</dependencies></dependencyManagement>",
                "<profiles><profile><dependencies>$dependency</dependencies></profile></profiles>",
            )) {
                val text = "<project>$section</project>"
                assertFailsWith<DependencyEditException> { preview("pom.xml", text) }
                assertEquals(text, myFixture.editor.document.text)
            }
        }
    }

    fun testMavenRejectsMalformedAmbiguousAndPropertyCoordinates() {
        for (text in listOf(
            "<project><dependencies></project>",
            "<project><dependencies/><dependencies/></project>",
            "<notProject/>",
            """<project xmlns="urn:other"/>""",
            "<project><dependencies><dependency><groupId>\${group}</groupId><artifactId>sample</artifactId></dependency></dependencies></project>",
            "<!DOCTYPE project [<!ENTITY x 'example'>]><project/>",
            "<project><dependencies><other/></dependencies></project>",
            "<project><dependencies><dependency><artifactId>missing-group</artifactId></dependency></dependencies></project>",
        )) {
            assertFailsWith<DependencyEditException> { preview("pom.xml", text) }
        }
    }

    fun testGroovyAndKotlinAppendTopLevelBlockAfterWholeScript() {
        for (name in listOf("build.gradle", "build.gradle.kts")) {
            val declaration = if (name.endsWith(".kts")) """implementation("example:sample:2.0")""" else "implementation 'example:sample:2.0'"
            val text = """plugins { id("java") }
dependencies {
    $declaration
}
tasks.register("example") {
    doLast { println("dependencies { bootui-spring-boot-starter }") }
}"""
            val result = preview(name, text)
            assertTrue(result.after.startsWith(text + "\n"))
            val expected = if (name.endsWith(".kts")) """runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.20.0")"""
            else "runtimeOnly 'com.julien-dubois.bootui:bootui-spring-boot-starter:1.20.0'"
            assertTrue(result.after.endsWith("\ndependencies {\n    $expected\n}\n"))
            assertTrue(BootUiDependencyEdit.apply(project, myFixture.editor.document, result))
            assertEquals(result.after, myFixture.editor.document.text)
            assertFailsWith<DependencyEditException> {
                BootUiDependencyEdit.preview(project, name, myFixture.editor.document, BootUiIntegration.MVC, "1.20.0")
            }
        }
    }

    fun testGradleCommentsAndUnrelatedStringsAreNotDependencies() {
        for (name in listOf("build.gradle", "build.gradle.kts")) {
            val text = """// dependencies { runtimeOnly("com.julien-dubois.bootui:bootui-spring-boot-starter:1.0.0") }
/* implementation("com.julien-dubois.bootui:bootui-quarkus:1.0.0") */
val unused = "com.julien-dubois.bootui:bootui-spring-boot-starter:1.0.0"
dependencies { }"""
            // Groovy uses def, Kotlin uses val.
            val input = if (name.endsWith(".kts")) text else text.replace("val unused", "def unused")
            val result = preview(name, input, BootUiIntegration.QUARKUS)
            assertContains(result.after, "implementation")
            assertTrue(BootUiDependencyEdit.apply(project, myFixture.editor.document, result))
        }
    }

    fun testGradleCreatesMissingDependencyBlockForBothDsls() {
        for (name in listOf("build.gradle", "build.gradle.kts")) {
            val result = preview(name, """plugins { id("java") }""", BootUiIntegration.WEBFLUX)
            assertTrue(BootUiDependencyEdit.apply(project, myFixture.editor.document, result))
            assertContains(myFixture.editor.document.text, "bootui-spring-boot-starter-reactive:1.20.0")
            assertFailsWith<DependencyEditException> {
                BootUiDependencyEdit.preview(project, name, myFixture.editor.document, BootUiIntegration.WEBFLUX, "1.20.0")
            }
        }
    }

    fun testGradleExistingConflictingAndVersionlessCoordinatesAreDetected() {
        for (name in listOf("build.gradle", "build.gradle.kts")) {
            for (artifact in BootUiIntegration.entries.map { it.artifact }) {
                val text = """dependencies { implementation("${BootUiDependencyEdit.GROUP}:$artifact") }"""
                assertFailsWith<DependencyEditException> { preview(name, text) }
                assertEquals(text, myFixture.editor.document.text)
            }
        }
    }

    fun testGradleCatalogsMapsVariablesClosuresAndMalformedScriptsFailClosed() {
        for (text in listOf(
            "dependencies { implementation(libs.bootui) }",
            "dependencies { implementation(coordinate) }",
            "dependencies { implementation(group: 'example', name: 'sample', version: '1.0') }",
            "dependencies { constraints { implementation('example:sample:1.0') } }",
            "dependencies { implementation('example:sample:1.0') { exclude group: 'x' } }",
            "dependencies { implementation(\"example:sample:\${version}\") }",
            "apply from: 'other.gradle'",
            "dependencies { implementation('example:sample:1.0')",
            "dependencies { implementation( }",
            "dependencies.add('implementation', 'example:sample:1.0')",
            """println("${'$'}{dependencies { implementation('com.julien-dubois.bootui:bootui-quarkus:1.0.0') }}")""",
        )) {
            assertFailsWith<DependencyEditException> { preview("build.gradle", text) }
            assertEquals(text, myFixture.editor.document.text)
        }
    }

    fun testVersionValidationAndIntegrationScopes() {
        myFixture.configureByText("build.gradle.kts", "")
        for (version in listOf("", "1.20.0-SNAPSHOT", "1.20.0\" injected", "\${version}", "latest.release")) {
            assertFailsWith<DependencyEditException> {
                BootUiDependencyEdit.preview(project, "build.gradle.kts", myFixture.editor.document, BootUiIntegration.MVC, version)
            }
        }
        for (integration in BootUiIntegration.entries) {
            val result = BootUiDependencyEdit.preview(project, "build.gradle.kts", myFixture.editor.document, integration, "1.20.0")
            assertContains(result.after, """${integration.configuration}("${BootUiDependencyEdit.GROUP}:${integration.artifact}:1.20.0")""")
        }
    }

    fun testPreviewDoesNotEditUnsavedDocumentAndStalePreviewCannotApply() {
        myFixture.configureByText("pom.xml", "<project/>")
        val document = myFixture.editor.document
        val unsaved = "<project><!-- unsaved --></project>"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(unsaved) }
        val result = BootUiDependencyEdit.preview(project, "pom.xml", document, BootUiIntegration.MVC, "1.20.0")
        assertEquals(unsaved, result.before)
        assertEquals(unsaved, document.text)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "\n") }
        assertFalse(BootUiDependencyEdit.apply(project, document, result))
        assertEquals("\n$unsaved", document.text)
    }

    fun testConfirmedEditIsOneUndoableCommand() {
        val result = preview("pom.xml", "<project/>")
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, myFixture.file.virtualFile), true)!!
        assertTrue(BootUiDependencyEdit.apply(project, editor.document, result))
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile)!!
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(fileEditor))
        undo.undo(fileEditor)
        assertEquals(result.before, editor.document.text)
        undo.redo(fileEditor)
        assertEquals(result.after, editor.document.text)
    }

    fun testDialogCancellationDoesNotEditDocument() {
        val file = myFixture.addFileToProject("pom.xml", "<project/>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val dialog = BootUiDependencyDialog(project, listOf(BootUiBuildFile(myFixture.file.virtualFile, "test — pom.xml"))) {}
        assertFalse(dialog.isOKActionEnabled)
        dialog.preview()
        assertTrue(dialog.isOKActionEnabled)
        assertEquals("<project/>", myFixture.editor.document.text)
        dialog.doCancelAction()
        assertEquals("<project/>", myFixture.editor.document.text)
    }

    fun testDialogStaleConfirmationRequiresNewPreview() {
        val file = myFixture.addFileToProject("pom.xml", "<project/>")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val dialog = BootUiDependencyDialog(project, listOf(BootUiBuildFile(file.virtualFile, "test — pom.xml"))) {}
        try {
            dialog.preview()
            assertTrue(dialog.isOKActionEnabled)
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "\n") }
            dialog.performOKAction()
            assertEquals("\n<project/>", myFixture.editor.document.text)
            assertFalse(dialog.isOKActionEnabled)
            dialog.preview()
            assertTrue(dialog.isOKActionEnabled)
            dialog.performOKAction()
            assertContains(myFixture.editor.document.text, "bootui-spring-boot-starter")
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testReadOnlyAndChangedScopeRefuseMutation() {
        val result = preview("pom.xml", "<project/>")
        val document = myFixture.editor.document
        assertFalse(BootUiDependencyEdit.apply(project, document, result) { false })
        document.setReadOnly(true)
        try {
            assertFalse(BootUiDependencyEdit.apply(project, document, result))
            assertEquals(result.before, document.text)
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testUntrustedProjectCannotApplyOrOpenInstaller() {
        val result = preview("pom.xml", "<project/>")
        val trusted = TrustedProjects.isProjectTrusted(project)
        try {
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))
            assertFalse(BootUiDependencyEdit.apply(project, myFixture.editor.document, result))
            var message = ""
            BootUiDependencyInstaller.open(project, testRootDisposable) { message = it }
            assertContains(message, "untrusted")
            assertEquals(result.before, myFixture.editor.document.text)
        } finally {
            TrustedProjects.setProjectTrusted(project, trusted)
        }
    }

    fun testDiscoveryExcludesBuildCachesVendorAndUnsupportedFiles() {
        val expected = listOf("pom.xml", "app/build.gradle", "other/build.gradle.kts").map {
            myFixture.addFileToProject(it, "").virtualFile
        }.toSet()
        for (path in listOf(
            "build/pom.xml", "target/build.gradle", ".gradle/build.gradle", "vendor/pom.xml",
            "node_modules/sample/build.gradle.kts", "generated/pom.xml", "settings.gradle.kts",
        )) myFixture.addFileToProject(path, "")
        val actual = BootUiDependencyInstaller.discover(project).map { it.file }.toSet()
        assertEquals(expected, actual)
    }
}
