package org.mobicents.protocols.sctp.spi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * One SCTP user message plus SIGTRAN envelope.
 * Hot path carries an FFM {@link MemorySegment} so GraalVM / sidecar can share
 * the packet without a heap {@code byte[]} copy.
 */
public record SctpRecord(
        long associationId,
        long generation,
        SctpEventKind kind,
        int streamId,
        int ppid,
        boolean unordered,
        int congestionLevel,
        int inboundStreams,
        int outboundStreams,
        MemorySegment payload
) {
    public static final int M3UA_PPID = 3;

    public SctpRecord {
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? MemorySegment.NULL : payload;
    }

    /** Test / admin path. Copies so the caller array can mutate independently. */
    public SctpRecord(long associationId, long generation, SctpEventKind kind, int streamId, int ppid,
            boolean unordered, int congestionLevel, int inboundStreams, int outboundStreams, byte[] bytes) {
        this(associationId, generation, kind, streamId, ppid, unordered, congestionLevel, inboundStreams,
                outboundStreams, copyOf(bytes));
    }

    public static SctpRecord wrap(long associationId, long generation, SctpEventKind kind, int streamId, int ppid,
            boolean unordered, int congestionLevel, int inboundStreams, int outboundStreams, MemorySegment segment) {
        return new SctpRecord(associationId, generation, kind, streamId, ppid, unordered, congestionLevel,
                inboundStreams, outboundStreams, segment);
    }

    public int payloadLength() {
        return payload == null || payload.byteSize() == 0 ? 0 : (int) payload.byteSize();
    }

    public MemorySegment payloadSegment() {
        return payload == null ? MemorySegment.NULL : payload;
    }

    /** Copies out. Prefer {@link #payload()} / {@link #payloadSegment()} on the dataplane. */
    public byte[] payloadBytes() {
        if (payloadLength() == 0) {
            return new byte[0];
        }
        return payload.toArray(ValueLayout.JAVA_BYTE);
    }

    public SctpRecord copy() {
        return new SctpRecord(associationId, generation, kind, streamId, ppid, unordered,
                congestionLevel, inboundStreams, outboundStreams, payloadBytes());
    }

    public boolean isM3ua() {
        return ppid == M3UA_PPID;
    }

    private static MemorySegment copyOf(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return MemorySegment.NULL;
        }
        return Arena.ofAuto().allocateFrom(ValueLayout.JAVA_BYTE, bytes);
    }
}
