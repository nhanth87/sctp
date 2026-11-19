package org.mobicents.protocols.sctp.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.sun.nio.sctp.Association;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.NotificationHandler;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpSocketOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.sctp.SctpMessage;

import org.testng.annotations.Test;

/**
 * With the socket send buffer full (peer a_rwnd 0) {@code SctpChannel.send} returns 0 and Netty
 * calls {@code doWriteMessage} again for the same message. Before 2.27.34 the first call had
 * already consumed the buffer, so the retry saw 0 readable bytes, answered "written" and the
 * M3UA message was dropped without any error.
 */
public class PooledNioSctpChannelWriteTest {

    /** Kernel stand-in: refuses the first {@code refusals} sends (buffer full), then accepts. */
    private static final class FullThenFreeSctpChannel extends SctpChannel {
        int refusals;
        int sendCalls;
        final List<byte[]> accepted = new ArrayList<>();

        FullThenFreeSctpChannel(int refusals) {
            super(SelectorProvider.provider());
            this.refusals = refusals;
        }

        @Override
        public int send(ByteBuffer src, MessageInfo messageInfo) {
            sendCalls++;
            if (refusals > 0) {
                refusals--;
                return 0;
            }
            byte[] bytes = new byte[src.remaining()];
            src.get(bytes);
            accepted.add(bytes);
            return bytes.length;
        }

        private final Association association = new Association(1, 16, 16) { };

        @Override public Association association() { return association; }
        @Override public SctpChannel bind(SocketAddress local) { return this; }
        @Override public SctpChannel bindAddress(InetAddress address) { return this; }
        @Override public SctpChannel unbindAddress(InetAddress address) { return this; }
        @Override public boolean connect(SocketAddress remote) { return true; }
        @Override public boolean connect(SocketAddress remote, int maxOutStreams, int maxInStreams) { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean finishConnect() { return true; }
        @Override public Set<SocketAddress> getAllLocalAddresses() { return Collections.emptySet(); }
        @Override public Set<SocketAddress> getRemoteAddresses() { return Collections.emptySet(); }
        @Override public SctpChannel shutdown() { return this; }
        @Override public <T> T getOption(SctpSocketOption<T> name) { return null; }
        @Override public <T> SctpChannel setOption(SctpSocketOption<T> name, T value) { return this; }
        @Override public Set<SctpSocketOption<?>> supportedOptions() { return Collections.emptySet(); }
        @Override public <T> MessageInfo receive(ByteBuffer dst, T attachment, NotificationHandler<T> handler) {
            return null;
        }
        @Override protected void implCloseSelectableChannel() throws IOException { }
        @Override protected void implConfigureBlocking(boolean block) throws IOException { }
    }

    private static byte[] payload(int len) {
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    /** Netty's AbstractNioMessageChannel.doWrite: retry the same message up to writeSpinCount times. */
    private static boolean writeLikeNetty(PooledNioSctpChannel channel, SctpMessage msg) throws Exception {
        for (int i = 0; i < 16; i++) {
            if (channel.doWriteMessage(msg, null)) {
                return true;
            }
        }
        return false;
    }

    private static void assertRetrySendsWholeMessage(ByteBuf content, byte[] expected) throws Exception {
        FullThenFreeSctpChannel kernel = new FullThenFreeSctpChannel(1);
        PooledNioSctpChannel channel = new PooledNioSctpChannel(kernel);
        SctpMessage msg = new SctpMessage(3, 1, content);
        try {
            assertTrue(writeLikeNetty(channel, msg));

            assertEquals("first send refused, second accepted", 2, kernel.sendCalls);
            assertEquals("the message reached the socket exactly once", 1, kernel.accepted.size());
            assertTrue("retry must carry the whole message", Arrays.equals(expected, kernel.accepted.get(0)));
        } finally {
            msg.release();
        }
    }

    @Test(groups = { "functional" })
    public void retryAfterFullSendBufferSendsTheMessage_directBuffer() throws Exception {
        byte[] bytes = payload(272);
        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length);
        direct.writeBytes(bytes);
        assertRetrySendsWholeMessage(direct, bytes);
    }

    @Test(groups = { "functional" })
    public void retryAfterFullSendBufferSendsTheMessage_heapBuffer() throws Exception {
        byte[] bytes = payload(272);
        assertRetrySendsWholeMessage(Unpooled.wrappedBuffer(bytes), bytes);
    }

    @Test(groups = { "functional" })
    public void stillFullAfterAllSpinsKeepsTheMessageForOpWrite() throws Exception {
        byte[] bytes = payload(64);
        FullThenFreeSctpChannel kernel = new FullThenFreeSctpChannel(Integer.MAX_VALUE);
        PooledNioSctpChannel channel = new PooledNioSctpChannel(kernel);
        SctpMessage msg = new SctpMessage(3, 1, Unpooled.wrappedBuffer(bytes));
        try {
            assertFalse("not written while the buffer stays full", writeLikeNetty(channel, msg));
            assertEquals("message kept intact for the OP_WRITE retry", bytes.length, msg.content().readableBytes());
            assertTrue(kernel.accepted.isEmpty());
        } finally {
            msg.release();
        }
    }
}
