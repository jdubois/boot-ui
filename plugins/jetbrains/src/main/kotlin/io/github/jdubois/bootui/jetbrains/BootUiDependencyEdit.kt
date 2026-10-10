package io.github.jdubois.bootui.jetbrains

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

internal enum class BootUiIntegration(val label: String, val artifact: String, val configuration: String) {
    MVC("Spring Boot 4 MVC", "bootui-spring-boot-starter", "runtimeOnly"),
    WEBFLUX("Spring Boot 4 WebFlux", "bootui-spring-boot-starter-reactive", "runtimeOnly"),
    QUARKUS("Quarkus", "bootui-quarkus", "implementation");

    override fun toString(): String = "$label — $artifact"
}

internal class DependencyEditException(message: String) : IllegalArgumentException(message)

internal data class DependencyPreview(
    val before: String,
    val after: String,
    val stamp: Long,
) {
    fun isCurrent(document: Document): Boolean =
        document.modificationStamp == stamp && document.text == before
}

internal object BootUiDependencyEdit {
    const val DEFAULT_VERSION = "1.20.0"
    const val GROUP = "com.julien-dubois.bootui"
    private val stableVersion = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")

    fun preview(
        project: Project,
        name: String,
        document: Document,
        integration: BootUiIntegration,
        version: String,
    ): DependencyPreview {
        if (!stableVersion.matches(version)) fail("Enter a stable numeric release version, for example 1.20.0.")
        val before = document.text
        if (before.length > 512 * 1024) fail("This build file exceeds the 512K-character editing limit.")
        val after = when (name) {
            "pom.xml" -> maven(project, before, integration, version)
            "build.gradle", "build.gradle.kts" -> gradle(project, name, before, integration, version)
            else -> fail("Select pom.xml, build.gradle, or build.gradle.kts.")
        }
        return DependencyPreview(before, after, document.modificationStamp)
    }

    // The caller obtains IDE write permission; every safety condition is rechecked inside the command.
    fun apply(
        project: Project,
        document: Document,
        preview: DependencyPreview,
        stillEligible: () -> Boolean = { true },
    ): Boolean {
        var applied = false
        WriteCommandAction.runWriteCommandAction(project, "Add BootUI dependency", null, Runnable {
            if (!project.isDisposed && TrustedProjects.isProjectTrusted(project) &&
                stillEligible() && preview.isCurrent(document) && document.isWritable) {
                document.setText(preview.after)
                applied = true
            }
        })
        return applied
    }

    private fun maven(project: Project, text: String, integration: BootUiIntegration, version: String): String {
        val file = parse(project, "pom.xml", text) as? XmlFile ?: fail("XML support is unavailable.")
        val root = file.rootTag ?: fail("The Maven build file has no project element.")
        if (root.localName != "project" || root.namespace !in setOf("", "http://maven.apache.org/POM/4.0.0")) {
            fail("Select a Maven project POM with the standard Maven namespace (or no namespace).")
        }
        if (file.document?.prolog?.doctype != null) fail("POMs with a DOCTYPE are not supported.")
        val allTags = PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
        for (dependency in allTags.filter { it.localName == "dependency" }) {
            val group = childValue(dependency, "groupId")
            val artifact = childValue(dependency, "artifactId")
            if (group.isNullOrBlank() || artifact.isNullOrBlank() || dependency.namespace != root.namespace) {
                fail("A dependency has missing coordinates or an unsupported namespace. Review the POM manually.")
            }
            if (group == GROUP || artifact.startsWith("bootui-")) {
                existing(artifact, integration)
            }
            if (group.contains("\${") || artifact.contains("\${")) {
                fail("A dependency uses property-based coordinates. Resolve it manually before adding BootUI.")
            }
        }
        val containers = root.subTags.filter { it.localName == "dependencies" }
        if (containers.size > 1) fail("The POM has multiple direct project/dependencies elements.")
        val container = containers.singleOrNull() ?: root
        if (container.namespace != root.namespace) fail("The dependencies element has an unsupported namespace.")
        if (containers.isNotEmpty() && container.subTags.any { it.localName != "dependency" }) {
            fail("The direct dependencies element contains unsupported child elements.")
        }
        val prefix = container.namespacePrefix.let { if (it.isEmpty()) "" else "$it:" }
        val snippet = """
            <${prefix}dependency>
              <${prefix}groupId>$GROUP</${prefix}groupId>
              <${prefix}artifactId>${integration.artifact}</${prefix}artifactId>
              <${prefix}version>$version</${prefix}version>
            </${prefix}dependency>
        """.trimIndent()
        val content = if (containers.isEmpty()) {
            "<${prefix}dependencies>\n${snippet.prependIndent("  ")}\n</${prefix}dependencies>"
        } else snippet
        val emptyEnd = container.children.filterIsInstance<XmlToken>()
            .firstOrNull { it.tokenType == XmlTokenType.XML_EMPTY_ELEMENT_END }
        if (emptyEnd != null) {
            val offset = emptyEnd.textRange.startOffset
            return text.replaceRange(offset, offset + 2, ">\n${content.prependIndent("  ")}\n</${container.name}>")
        }
        val end = container.children.filterIsInstance<XmlToken>()
            .firstOrNull { it.tokenType == XmlTokenType.XML_END_TAG_START }
            ?: fail("The selected XML element is incomplete.")
        val offset = end.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val indentation = text.substring(lineStart, offset).takeIf { it.isBlank() } ?: ""
        val insertionOffset = if (indentation.isNotEmpty() || lineStart == offset) lineStart else offset
        val insertion = (if (insertionOffset == offset && lineStart != offset) "\n" else "") +
            "${content.prependIndent("$indentation  ")}\n"
        return text.substring(0, insertionOffset) + insertion + text.substring(insertionOffset)
    }

    private fun childValue(tag: XmlTag, name: String): String? {
        val matches = tag.subTags.filter { it.localName == name }
        if (matches.size > 1) fail("A dependency contains repeated $name elements.")
        return matches.singleOrNull()?.value?.trimmedText
    }

    private fun parse(project: Project, name: String, text: String): com.intellij.psi.PsiFile {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val file = PsiFileFactory.getInstance(project).createFileFromText(name, type, text)
        if (file.language.id !in setOf("XML", "Groovy", "kotlin")) fail("The IDE cannot parse this build file.")
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) {
            fail("The build file contains syntax errors. Fix them before adding BootUI.")
        }
        return file
    }

    private fun gradle(project: Project, name: String, text: String, integration: BootUiIntegration, version: String): String {
        parse(project, name, text)
        GradleDependencyInspection.check(text, integration)
        val coordinate = "$GROUP:${integration.artifact}:$version"
        val declaration = if (name.endsWith(".kts")) "${integration.configuration}(\"$coordinate\")"
        else "${integration.configuration} '$coordinate'"
        return text + (if (text.endsWith("\n") || text.isEmpty()) "" else "\n") +
            "\ndependencies {\n    $declaration\n}\n"
    }

    internal fun existing(artifact: String?, integration: BootUiIntegration): Nothing {
        if (artifact == integration.artifact) {
            fail("BootUI is already declared in this file (possibly managed or profile-specific). No version or scope is changed.")
        }
        fail("Another BootUI dependency is present. Review it manually; MVC and WebFlux starters must not be combined.")
    }

    internal fun fail(message: String): Nothing = throw DependencyEditException(message)
}

/**
 * Inspect a deliberately small literal dependency DSL, not arbitrary executable Gradle code.
 * Syntax is checked by the IDE first; this lexer keeps comments and unrelated string contents out
 * of dependency detection. Anything requiring execution/catalog resolution fails closed.
 */
internal object GradleDependencyInspection {
    private data class Token(val value: String, val literal: Boolean = false)
    private val configurations = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly", "testImplementation", "testRuntimeOnly",
        "testCompileOnly", "annotationProcessor", "kapt", "classpath", "compile", "runtime",
    )

    fun check(text: String, integration: BootUiIntegration) {
        val tokens = tokenize(text)
        if (tokens.any { !it.literal && it.value in setOf("libs", "apply", "versionCatalogs", "evaluate", "add") }) {
            BootUiDependencyEdit.fail("Catalogs, applied scripts, and dynamic dependency APIs require manual review; no edit was made.")
        }
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.literal || token.value != "dependencies") {
                index++
                continue
            }
            if (tokens.getOrNull(index + 1)?.value != "{") {
                BootUiDependencyEdit.fail("Only literal dependencies { ... } declarations are supported.")
            }
            index += 2
            while (tokens.getOrNull(index)?.value != "}") {
                val configuration = tokens.getOrNull(index++) ?: unsupported()
                if (configuration.literal || configuration.value !in configurations) unsupported()
                val parenthesized = tokens.getOrNull(index)?.value == "("
                if (parenthesized) index++
                val coordinate = tokens.getOrNull(index++) ?: unsupported()
                if (!coordinate.literal || coordinate.value.contains('$') || coordinate.value.contains('\\')) unsupported()
                val parts = coordinate.value.split(':')
                if (parts.size !in 2..4 || parts.any { it.isBlank() }) unsupported()
                if (parts[0] == BootUiDependencyEdit.GROUP || parts[1].startsWith("bootui-")) {
                    BootUiDependencyEdit.existing(parts[1], integration)
                }
                if (parenthesized) {
                    if (tokens.getOrNull(index++)?.value != ")") unsupported()
                }
                if (tokens.getOrNull(index)?.value == ";") index++
            }
            index++
        }
    }

    private fun unsupported(): Nothing = BootUiDependencyEdit.fail(
        "A dependencies block is not a simple literal-coordinate block (for example a catalog, variable, constraint, map, or closure). Review it manually.",
    )

    private fun tokenize(text: String): List<Token> {
        val result = mutableListOf<Token>()
        val brackets = ArrayDeque<Char>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                text.startsWith("//", i) -> {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                }
                text.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < text.length && depth > 0) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    if (depth != 0) unsupported()
                }
                c == '\'' || c == '"' -> {
                    val delimiter = if (text.startsWith("$c$c$c", i)) "$c$c$c" else "$c"
                    i += delimiter.length
                    val start = i
                    while (i < text.length && !text.startsWith(delimiter, i)) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (i >= text.length) unsupported()
                    val value = text.substring(start, i)
                    if (value.contains('$')) {
                        BootUiDependencyEdit.fail("Interpolated Gradle strings may execute dynamic dependency declarations. Review this script manually.")
                    }
                    result.add(Token(value, true))
                    i += delimiter.length
                }
                c.isLetterOrDigit() || c == '_' -> {
                    val start = i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    result.add(Token(text.substring(start, i)))
                }
                else -> {
                    // Slashy/dollar-slashy Groovy strings and backtick identifiers cannot be inspected reliably.
                    if (c == '/' || c == '`') unsupported()
                    if (c in "{([") brackets.addLast(c)
                    if (c in "})]") {
                        val expected = when (c) { '}' -> '{'; ')' -> '('; else -> '[' }
                        if (brackets.removeLastOrNull() != expected) unsupported()
                    }
                    result.add(Token(c.toString()))
                    i++
                }
            }
        }
        if (brackets.isNotEmpty()) unsupported()
        return result
    }
}
