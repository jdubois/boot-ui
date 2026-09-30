/**
 * Framework-neutral, explicit-scan-only lookup of an application class's own local source file.
 *
 * <p>Plain Java ({@code java.nio} only). {@link io.github.jdubois.bootui.engine.source.LocalSourceModule} derives the
 * Maven or Gradle module and source set from where a class file was compiled, and
 * {@link io.github.jdubois.bootui.engine.source.SourceLocator} resolves it to exactly one source file within bounded,
 * symlink-refusing budgets. The Architecture advisor's generated-source provenance and the advisor violation
 * locations share this one lookup; nothing here runs on a read path.
 */
package io.github.jdubois.bootui.engine.source;
