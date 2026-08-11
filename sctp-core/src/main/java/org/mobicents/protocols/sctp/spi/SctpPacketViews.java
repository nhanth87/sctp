package org.mobicents.protocols.sctp.spi;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import org.mobicents.protocols.api.PayloadData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * GraalVM-friendly views: wrap Netty {@link ByteBuf} / FFM segments without a
 * heap copy when the buffer is already array-backed or direct.
 */
public final class SctpPacketViews {
    private SctpPacketViews() {}

    public static MemorySegment viewOf(PayloadData data) {
        if (data == null) {
            return MemorySegment.NULL;
        }
        return viewOf(data.getByteBuf());
    }

    public static MemorySegment viewOf(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0) {
            return MemorySegment.NULL;
        }
        int readable = buf.readableBytes();
        if (buf.hasArray()) {
            return MemorySegment.ofArray(buf.array())
                    .asSlice(buf.arrayOffset() + buf.readerIndex(), readable);
        }
        if (buf.nioBufferCount() == 1) {
            ByteBuffer nio = buf.nioBuffer(buf.readerIndex(), readable);
            return MemorySegment.ofBuffer(nio);
        }
        byte[] tmp = new byte[readable];
        buf.getBytes(buf.readerIndex(), tmp);
        return MemorySegment.ofArray(tmp);
    }

    public static PayloadData wrap(SctpRecord record) {
        MemorySegment segment = record.payloadSegment();
        ByteBuf buf = asByteBuf(segment);
        return new PayloadData(record.payloadLength(), buf, true, record.unordered(),
                record.ppid(), record.streamId());
    }

    public static ByteBuf asByteBuf(MemorySegment segment) {
        if (segment == null || segment.byteSize() == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        return Unpooled.wrappedBuffer(segment.asByteBuffer());
    }
}
