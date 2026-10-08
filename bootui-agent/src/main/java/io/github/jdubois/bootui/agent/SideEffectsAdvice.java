package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.Blocking;
import io.github.jdubois.bootui.agent.bridge.SecuritySinks;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.net.DatagramPacket;
import java.net.SocketAddress;
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

    /**
     * {@code Socket.connect(SocketAddress,int)}, which every blocking {@code Socket} connect reaches, {@code
     * SSLSocketImpl.connect} through {@code super}. Reads only the endpoint argument, never calls the socket, which it
     * passes as an identity key for the resources sensor.
     */
    static final class SocketConnect {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object socket,
                @Advice.Argument(0) SocketAddress endpoint,
                @Advice.Thrown Throwable thrown) {
            SideEffects.connected(token, SideEffects.HOOK_SOCKET_CONNECT, null, endpoint, true, socket, thrown);
        }
    }

    /**
     * {@code SocketChannelImpl.connect(SocketAddress)}: its result false is a non-blocking connect started. The channel
     * is passed as an identity key only.
     */
    static final class ChannelConnect {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_CONNECT);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object channel,
                @Advice.Argument(0) SocketAddress remote,
                @Advice.Return boolean finished,
                @Advice.Thrown Throwable thrown) {
            SideEffects.connected(token, SideEffects.HOOK_CHANNEL_CONNECT, channel, remote, finished, channel, thrown);
        }
    }

    /** {@code SocketChannelImpl.blockingConnect(SocketAddress,long)}, which {@code SocketChannel.socket().connect} reaches. */
    static final class ChannelBlockingConnect {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_BLOCKING_CONNECT);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object channel,
                @Advice.Argument(0) SocketAddress remote,
                @Advice.Thrown Throwable thrown) {
            SideEffects.connected(
                    token, SideEffects.HOOK_CHANNEL_BLOCKING_CONNECT, null, remote, true, channel, thrown);
        }
    }

    /** {@code SocketChannelImpl.finishConnect()}: a non-blocking connect's outcome and time. */
    static final class ChannelFinishConnect {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_FINISH_CONNECT);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object channel,
                @Advice.Return boolean finished,
                @Advice.Thrown Throwable thrown) {
            SideEffects.connectFinished(token, channel, finished, thrown);
        }
    }

    /** {@code DatagramChannelImpl.send(ByteBuffer,SocketAddress)}: never reads the buffer. */
    static final class DatagramChannelSend {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_CHANNEL_SEND);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token, @Advice.Argument(1) SocketAddress target, @Advice.Thrown Throwable thrown) {
            SideEffects.datagramSent(token, SideEffects.HOOK_DATAGRAM_CHANNEL_SEND, target, thrown);
        }
    }

    /** {@code DatagramSocket.send(DatagramPacket)}: the bridge reads the packet's address and port, never its data. */
    static final class DatagramSocketSend {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token, @Advice.Argument(0) DatagramPacket packet, @Advice.Thrown Throwable thrown) {
            SideEffects.datagramSent(token, SideEffects.HOOK_DATAGRAM_SOCKET_SEND, packet, thrown);
        }
    }

    /**
     * {@code InetAddress.getAddressesFromNameService(String, …)}, static, reached only when the JVM's address cache
     * misses: {@code (String, InetAddress)} on JDK 17, {@code (String)} since JDK 18.
     */
    static final class Lookup {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.Argument(0) String host,
                @Advice.Return Object addresses,
                @Advice.Thrown Throwable thrown) {
            SideEffects.lookedUp(token, host, addresses, thrown);
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
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object stream,
                @Advice.Argument(0) String name,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.fileOpened(
                        token, SideEffects.HOOK_FILE_INPUT_STREAM, SideEffects.KIND_FILE_READ, name, stream, thrown);
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
        static void exit(
                @Advice.Enter long token,
                @Advice.This Object stream,
                @Advice.Argument(0) String name,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.fileOpened(
                        token, SideEffects.HOOK_FILE_OUTPUT_STREAM, SideEffects.KIND_FILE_WRITE, name, stream, thrown);
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
                @Advice.This Object file,
                @Advice.Argument(0) String name,
                @Advice.Argument(1) int mode,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.randomAccessOpened(token, name, mode, file, thrown);
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
                @Advice.Return Object channel,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.channelOpened(token, SideEffects.HOOK_NEW_BYTE_CHANNEL, path, options, channel, thrown);
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
                @Advice.Return Object channel,
                @Advice.Thrown Throwable thrown) {
            if (token != 0L) {
                SideEffects.channelOpened(token, SideEffects.HOOK_FILE_CHANNEL, path, options, channel, thrown);
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
                SideEffects.environmentRead(SideEffects.HOOK_GETENV_ALL, SideEffects.KIND_ENVIRONMENT_VARIABLE, null);
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

    // ---- blocking (M5-5c) ------------------------------------------------------------------------------------------

    /**
     * {@code LockSupport.park}, {@code parkNanos}, and {@code parkUntil}, with and without a blocker: the bridge returns
     * at entry off event loops. The exit also runs when the park throws, as BlockHound's callback does from inside it,
     * so the thread's hook is always closed.
     */
    static final class Park {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return Blocking.parking();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Thrown Throwable thrown) {
            Blocking.parked(token, thrown);
        }
    }

    /**
     * {@code MessageDigest.getInstance}, every overload: the algorithm only, at entry, so a digest the JDK refuses is
     * seen as asked for.
     */
    static final class DigestGetInstance {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) String algorithm) {
            SecuritySinks.digest(algorithm);
        }
    }

    /** {@code Cipher.getInstance(String)} and {@code getInstance(String, Provider)}: the transformation, at entry. */
    static final class CipherGetInstance {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) String transformation) {
            SecuritySinks.cipher(transformation);
        }
    }

    /** {@code ObjectInputStream.readObject()}: the stream at entry, for its filter; the outermost call ends at exit. */
    static final class ReadObject {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter(@Advice.This java.io.ObjectInputStream stream) {
            return SecuritySinks.reading(stream);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Thrown Throwable thrown) {
            SecuritySinks.read(token, thrown);
        }
    }

    /** {@code ObjectInputStream.resolveClass}: the class it resolved, on a normal return only. */
    static final class ResolveClass {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.Return Class<?> resolved) {
            SecuritySinks.resolved(resolved);
        }
    }

    /** {@code SSLContext.init}: the trust managers, at entry, never called. */
    static final class SslContextInit {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(1) Object[] managers) {
            SecuritySinks.sslInit(managers);
        }
    }

    /** {@code HttpsURLConnection.setDefaultHostnameVerifier}: the verifier, at entry, never called. */
    static final class DefaultHostnameVerifier {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) Object verifier) {
            SecuritySinks.defaultVerifier(verifier);
        }
    }

    /** {@code HttpsURLConnection.setDefaultSSLSocketFactory}: the factory, at entry, never called. */
    static final class DefaultSocketFactory {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) Object factory) {
            SecuritySinks.defaultFactory(factory);
        }
    }
}
