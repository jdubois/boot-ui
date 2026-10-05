package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;

/**
 * The side-effect sensors' delegating advice (PLAN-v2 §5.16, M5-5): one static call into the bridge at entry and one
 * at exit of each hooked JDK method, both suppressing their own exceptions, so the advice never changes what the method
 * does or throws.
 */
final class SideEffectsAdvice {

    private SideEffectsAdvice() {}

    /**
     * {@code ProcessBuilder.start(Redirect[])}, which {@code start()}, {@code startPipeline}, and {@code Runtime.exec}
     * all reach.
     */
    static final class ProcessStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.processStarting();
        }

        /**
         * Reads the {@code command} field, never calls {@code command()}, so a Mockito spy of {@code ProcessBuilder}
         * records no extra interaction.
         */
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.FieldValue("command") List<String> command,
                @Advice.Return Process process,
                @Advice.Thrown Throwable thrown) {
            SideEffects.processStarted(token, command, process, thrown);
        }
    }

    // ---- files (M5-5d): the entry checks the gate inline, so a hook whose sensor is off calls nothing ------------

    /** {@code FileInputStream.open(String)}, which every constructor reaches: a read. */
    static final class FileInputStreamOpen {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) String name, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.fileOpened(
                        token, SideEffects.HOOK_FILE_INPUT_STREAM, SideEffects.KIND_FILE_READ, name, thrown);
            }
        }
    }

    /** {@code FileOutputStream.open(String, boolean)}, which every constructor reaches: a write. */
    static final class FileOutputStreamOpen {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_FILE_OUTPUT_STREAM);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) String name, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.fileOpened(
                        token, SideEffects.HOOK_FILE_OUTPUT_STREAM, SideEffects.KIND_FILE_WRITE, name, thrown);
            }
        }
    }

    /** {@code RandomAccessFile.open(String, int)}: read or write by the mode. */
    static final class RandomAccessFileOpen {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_RANDOM_ACCESS_FILE);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) String name,
                @Advice.Argument(1) int mode,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.randomAccessOpened(token, name, mode, thrown);
            }
        }
    }

    /** {@code Files.newByteChannel(Path, Set, FileAttribute[])}, which the varargs overload and most reads reach. */
    static final class NewByteChannel {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_NEW_BYTE_CHANNEL);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) Object path,
                @Advice.Argument(1) Set<?> options,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.channelOpened(token, SideEffects.HOOK_NEW_BYTE_CHANNEL, path, options, thrown);
            }
        }
    }

    /** {@code FileChannel.open(Path, Set, FileAttribute[])}, which the varargs overload reaches. */
    static final class FileChannelOpen {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_FILE_CHANNEL);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) Object path,
                @Advice.Argument(1) Set<?> options,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.channelOpened(token, SideEffects.HOOK_FILE_CHANNEL, path, options, thrown);
            }
        }
    }

    /** {@code Files.newInputStream(Path, OpenOption[])}: a read. */
    static final class NewInputStream {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_NEW_INPUT_STREAM);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) Object path, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathUsed(
                        token, SideEffects.HOOK_NEW_INPUT_STREAM, SideEffects.KIND_FILE_READ, path, thrown);
            }
        }
    }

    /** {@code Files.newOutputStream(Path, OpenOption[])}: a write. */
    static final class NewOutputStream {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_NEW_OUTPUT_STREAM);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) Object path, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathUsed(
                        token, SideEffects.HOOK_NEW_OUTPUT_STREAM, SideEffects.KIND_FILE_WRITE, path, thrown);
            }
        }
    }

    /** {@code Files.delete(Path)}. */
    static final class Delete {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_DELETE);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) Object path, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathUsed(token, SideEffects.HOOK_DELETE, SideEffects.KIND_FILE_DELETE, path, thrown);
            }
        }
    }

    /** {@code Files.deleteIfExists(Path)}. */
    static final class DeleteIfExists {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_DELETE_IF_EXISTS);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Argument(0) Object path, @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathUsed(
                        token, SideEffects.HOOK_DELETE_IF_EXISTS, SideEffects.KIND_FILE_DELETE, path, thrown);
            }
        }
    }

    /** {@code Files.move(Path, Path, CopyOption[])}: the source moved from, the destination moved to. */
    static final class Move {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_MOVE);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) Object source,
                @Advice.Argument(1) Object target,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathsUsed(
                        token,
                        SideEffects.HOOK_MOVE,
                        SideEffects.KIND_FILE_MOVE_FROM,
                        source,
                        SideEffects.KIND_FILE_MOVE_TO,
                        target,
                        thrown);
            }
        }
    }

    /**
     * The three {@code Files.copy} overloads, {@code (Path, Path, CopyOption[])}, {@code (InputStream, Path,
     * CopyOption[])}, and {@code (Path, OutputStream)}: each path argument copied from or to.
     */
    static final class Copy {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return (SideEffects.gate() & SideEffects.MASK_FILES) == 0
                    ? 0L
                    : SideEffects.fileOpening(SideEffects.HOOK_COPY);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) Object source,
                @Advice.Argument(1) Object target,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.pathsUsed(
                        token,
                        SideEffects.HOOK_COPY,
                        SideEffects.KIND_FILE_COPY_FROM,
                        source,
                        SideEffects.KIND_FILE_COPY_TO,
                        target,
                        thrown);
            }
        }
    }

    // ---- environment (M5-5d): entry only, the name argument only, never the value or a default ----------------

    /** {@code System.getenv(String)}. */
    static final class GetenvName {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) String name) {
            if ((SideEffects.gate() & SideEffects.MASK_ENVIRONMENT) != 0) {
                SideEffects.environmentRead(
                        SideEffects.HOOK_GETENV, SideEffects.KIND_ENVIRONMENT_VARIABLE, name == null ? "" : name);
            }
        }
    }

    /** {@code System.getenv()}: every variable at once. */
    static final class GetenvAll {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() {
            if ((SideEffects.gate() & SideEffects.MASK_ENVIRONMENT) != 0) {
                SideEffects.environmentRead(SideEffects.HOOK_GETENV, SideEffects.KIND_ENVIRONMENT_VARIABLE, null);
            }
        }
    }

    /** {@code System.getProperty(String)} and {@code System.getProperty(String, String)}: the key only. */
    static final class GetProperty {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) String key) {
            if ((SideEffects.gate() & SideEffects.MASK_ENVIRONMENT) != 0) {
                SideEffects.environmentRead(
                        SideEffects.HOOK_GET_PROPERTY, SideEffects.KIND_SYSTEM_PROPERTY, key == null ? "" : key);
            }
        }
    }
}
