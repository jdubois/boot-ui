package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.Resources;
import net.bytebuddy.asm.Advice;

/**
 * The {@code resources} sensor's delegating advice (PLAN-v2 §5.16, M5-5g): one static call into the bridge at the exit
 * of each close, normal or not, with the closed object as an identity key only, never called; and one at the entry of
 * {@code FileChannelImpl.setUninterruptible}, which a provider's {@code newInputStream} and {@code newOutputStream}
 * call on the channel under the stream they return. Each suppresses its own exceptions; the opens are the {@code files}
 * and {@code network} sensors' hooks ({@link SideEffectsAdvice}).
 */
final class ResourcesAdvice {

    private ResourcesAdvice() {}

    /** {@code FileInputStream.close()}, which its channel's close and its subclasses' {@code super.close()} reach. */
    static final class FileInputStreamClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object stream) {
            Resources.closed(stream, Resources.HOOK_FILE_INPUT_STREAM_CLOSE);
        }
    }

    /** {@code FileOutputStream.close()}. */
    static final class FileOutputStreamClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object stream) {
            Resources.closed(stream, Resources.HOOK_FILE_OUTPUT_STREAM_CLOSE);
        }
    }

    /** {@code RandomAccessFile.close()}. */
    static final class RandomAccessFileClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object file) {
            Resources.closed(file, Resources.HOOK_RANDOM_ACCESS_FILE_CLOSE);
        }
    }

    /** {@code FileChannelImpl.implCloseChannel()}, which a close and a thread's interruption both reach. */
    static final class FileChannelClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object channel) {
            Resources.closed(channel, Resources.HOOK_FILE_CHANNEL_CLOSE);
        }
    }

    /** {@code Socket.close()}, which a TLS socket's close and a socket stream's close reach. */
    static final class SocketClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object socket) {
            Resources.closed(socket, Resources.HOOK_SOCKET_CLOSE);
        }
    }

    /** {@code AbstractSelectableChannel.implCloseChannel()}: a socket channel's close or interruption. */
    static final class SelectableChannelClose {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.This Object channel) {
            Resources.closed(channel, Resources.HOOK_SELECTABLE_CHANNEL_CLOSE);
        }
    }

    /** {@code FileChannelImpl.setUninterruptible()}: the channel a provider's stream opened. */
    static final class Uninterruptible {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object channel) {
            Resources.uninterruptible(channel);
        }
    }
}
