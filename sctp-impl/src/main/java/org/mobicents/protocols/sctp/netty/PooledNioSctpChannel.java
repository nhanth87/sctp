package org.mobicents.protocols.sctp.netty;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.NotificationHandler;
import com.sun.nio.sctp.SctpChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.sctp.SctpMessage;
import io.netty.channel.sctp.SctpNotificationHandler;
import io.netty.channel.sctp.nio.NioSctpChannel;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Custom NioSctpChannel that pools direct buffers for read/write instead of
 * calling ByteBuffer.allocateDirect() on the hot path.
 *
 * v2.0.13 Performance Improvements:
 * - ThreadLocal output buffer pooling to reduce allocations
 * - Optimized fast path for direct single-buffer writes
 * - Pre-calculated buffer positions
 *
 * On Java 11 (our target runtime) we do not need the Java 22-24 MemorySegment
 * work-around, so we can skip the extra copy to inputCopy/outputCopy entirely.
 */
public class PooledNioSctpChannel extends NioSctpChannel {

    private final NotificationHandler notificationHandler;
    
    // ThreadLocal output buffer for reduced allocation overhead (v2.0.13)
    private static final int OUTPUT_BUFFER_SIZE = 8192;
    private static final ThreadLocal<ByteBuf> OUTPUT_BUFFER_POOL = new ThreadLocal<ByteBuf>() {
        @Override
        protected ByteBuf initialValue() {
            return PooledByteBufAllocator.DEFAULT.directBuffer(OUTPUT_BUFFER_SIZE);
        }
    };

    public PooledNioSctpChannel() {
        super();
        this.notificationHandler = new SctpNotificationHandler(this);
    }

    public PooledNioSctpChannel(SctpChannel sctpChannel) {
        super(sctpChannel);
        this.notificationHandler = new SctpNotificationHandler(this);
    }

    public PooledNioSctpChannel(Channel parent, SctpChannel sctpChannel) {
        super(parent, sctpChannel);
        this.notificationHandler = new SctpNotificationHandler(this);
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        SctpChannel ch = javaChannel();
        RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        ByteBuf buffer = allocHandle.allocate(config().getAllocator());
        boolean free = true;
        try {
            ByteBuffer data = buffer.internalNioBuffer(buffer.writerIndex(), buffer.writableBytes());
            int pos = data.position();

            MessageInfo messageInfo = ch.receive(data, null, notificationHandler);
            if (messageInfo == null) {
                return 0;
            }

            int bytesRead = data.position() - pos;
            allocHandle.lastBytesRead(bytesRead);
            buf.add(new SctpMessage(messageInfo,
                    buffer.writerIndex(buffer.writerIndex() + bytesRead)));
            free = false;
            return 1;
        } catch (Throwable cause) {
            PlatformDependent.throwException(cause);
            return -1;
        } finally {
            if (free) {
                buffer.release();
            }
        }
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        SctpMessage packet = (SctpMessage) msg;
        ByteBuf data = packet.content();
        int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        ByteBuffer nioData;
        ByteBuf pooled = null;
        
        // Fast path: direct buffer with single NIO buffer - use ThreadLocal pooling (v2.0.13)
        if (data.isDirect() && data.nioBufferCount() == 1) {
            ByteBuf outputBuf = OUTPUT_BUFFER_POOL.get();
            if (outputBuf.capacity() < dataLen) {
                // Expand if needed but try to reuse as much as possible
                outputBuf.capacity(dataLen);
            }
            outputBuf.clear();
            data.readBytes(outputBuf);
            nioData = outputBuf.internalNioBuffer(outputBuf.readerIndex(), outputBuf.readableBytes());
        } else {
            // Slow path: need to copy/convert
            pooled = config().getAllocator().directBuffer(dataLen);
            data.readBytes(pooled);
            nioData = pooled.internalNioBuffer(pooled.readerIndex(), pooled.readableBytes());
        }

        final MessageInfo mi = MessageInfo.createOutgoing(association(), null, packet.streamIdentifier());
        mi.payloadProtocolID(packet.protocolIdentifier());
        mi.streamNumber(packet.streamIdentifier());
        mi.unordered(packet.isUnordered());

        try {
            final int writtenBytes = javaChannel().send(nioData, mi);
            return writtenBytes > 0;
        } finally {
            if (pooled != null) {
                pooled.release();
            }
        }
    }
}