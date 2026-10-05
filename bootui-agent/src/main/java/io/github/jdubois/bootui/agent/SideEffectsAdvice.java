package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.util.List;
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
     * SSLSocketImpl.connect} through {@code super}. Reads only the endpoint argument, never calls the socket.
     */
    static final class SocketConnect {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token, @Advice.Argument(0) SocketAddress endpoint, @Advice.Thrown Throwable thrown) {
            SideEffects.connected(token, SideEffects.HOOK_SOCKET_CONNECT, null, endpoint, true, thrown);
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
            SideEffects.connected(token, SideEffects.HOOK_CHANNEL_CONNECT, channel, remote, finished, thrown);
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
                @Advice.Enter long token, @Advice.Argument(0) SocketAddress remote, @Advice.Thrown Throwable thrown) {
            SideEffects.connected(token, SideEffects.HOOK_CHANNEL_BLOCKING_CONNECT, null, remote, true, thrown);
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
}
